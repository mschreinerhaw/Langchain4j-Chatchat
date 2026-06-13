package com.chatchat.chat.conversation;

import java.time.LocalDateTime;

public record ConversationSummary(
    String id,
    String sessionId,
    String summary,
    String messageStartId,
    String messageEndId,
    LocalDateTime createdAt
) {
}
