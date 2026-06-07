package com.chatchat.api.conversation;

import java.time.Instant;

final class ChatMessageKeyBuilder {

    private ChatMessageKeyBuilder() {
    }

    static String build(String tenantId, String sessionId, Instant createdAt, String messageId) {
        long timestamp = createdAt == null ? Instant.now().toEpochMilli() : createdAt.toEpochMilli();
        return "chat:%s:%s:%013d:%s".formatted(safe(tenantId), safe(sessionId), timestamp, safe(messageId));
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.trim().replace(':', '_');
    }
}
