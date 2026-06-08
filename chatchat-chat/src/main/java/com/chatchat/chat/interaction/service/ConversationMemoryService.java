package com.chatchat.chat.interaction.service;

import com.chatchat.chat.conversation.Conversation;
import com.chatchat.chat.conversation.ConversationService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Persistent conversation memory facade to support interaction orchestration.
 */
@Service
public class ConversationMemoryService {

    private final ConversationService conversationService;

    public ConversationMemoryService(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    public String ensureConversationId(String conversationId) {
        return ensureConversationId(conversationId, null);
    }

    public String ensureConversationId(String conversationId, String userId) {
        return conversationService.ensureConversationId(conversationId, userId);
    }

    public void append(String conversationId, String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        conversationService.appendMessage(conversationId, role, content);
    }

    public List<MessageSnapshot> recent(String conversationId, int limit) {
        return conversationService.recentMessages(conversationId, limit).stream()
            .map(this::toSnapshot)
            .toList();
    }

    private MessageSnapshot toSnapshot(Conversation.Message message) {
        long timestamp = message.getTimestamp() == null
            ? System.currentTimeMillis()
            : message.getTimestamp().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new MessageSnapshot(message.getRole(), message.getContent(), timestamp);
    }

    public record MessageSnapshot(String role, String content, long timestamp) {
    }
}
