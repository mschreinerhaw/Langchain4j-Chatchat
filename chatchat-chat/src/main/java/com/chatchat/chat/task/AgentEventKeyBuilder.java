package com.chatchat.chat.task;

final class AgentEventKeyBuilder {

    /**
     * Creates a new AgentEventKeyBuilder instance.
     */
    private AgentEventKeyBuilder() {
    }

    /**
     * Builds the build.
     *
     * @param event the event value
     * @return the built build
     */
    static String build(AgentEvent event) {
        long timestamp = event.getCreateTime() <= 0 ? System.currentTimeMillis() : event.getCreateTime();
        long sequence = event.getSequence() == null || event.getSequence() <= 0 ? 0L : event.getSequence();
        return "tenant:%s:session:%s:task:%s:seq:%019d:time:%013d:event:%s".formatted(
            safe(event.getTenantId()),
            safe(event.getSessionId()),
            safe(event.getTaskId()),
            sequence,
            timestamp,
            safe(event.getEventId())
        );
    }

    /**
     * Performs the session prefix operation.
     *
     * @param tenantId the tenant id value
     * @param sessionId the session id value
     * @return the operation result
     */
    static String sessionPrefix(String tenantId, String sessionId) {
        return "tenant:%s:session:%s:".formatted(safe(tenantId), safe(sessionId));
    }

    /**
     * Performs the task prefix operation.
     *
     * @param tenantId the tenant id value
     * @param sessionId the session id value
     * @param taskId the task id value
     * @return the operation result
     */
    static String taskPrefix(String tenantId, String sessionId, String taskId) {
        return "tenant:%s:session:%s:task:%s:".formatted(safe(tenantId), safe(sessionId), safe(taskId));
    }

    /**
     * Performs the safe operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Agent event key component cannot be empty");
        }
        return value.trim().replace(':', '_');
    }
}
