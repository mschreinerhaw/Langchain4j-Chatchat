package com.chatchat.chat.conversation;

import java.time.Instant;

final class ChatMessageKeyBuilder {

    /**
     * Creates a new ChatMessageKeyBuilder instance.
     */
    private ChatMessageKeyBuilder() {
    }

    /**
     * Builds the build.
     *
     * @param tenantId the tenant id value
     * @param sessionId the session id value
     * @param createdAt the created at value
     * @param messageId the message id value
     * @return the built build
     */
    static String build(String tenantId, String sessionId, Instant createdAt, String messageId) {
        long timestamp = createdAt == null ? Instant.now().toEpochMilli() : createdAt.toEpochMilli();
        return "chat:%s:%s:%013d:%s".formatted(safe(tenantId), safe(sessionId), timestamp, safe(messageId));
    }

    /**
     * Performs the safe operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.trim().replace(':', '_');
    }
}
