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

    /**
     * Creates a new ConversationMemoryService instance.
     *
     * @param conversationService the conversation service value
     */
    public ConversationMemoryService(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * Ensures the conversation id.
     *
     * @param conversationId the conversation id value
     * @return the operation result
     */
    public String ensureConversationId(String conversationId) {
        return ensureConversationId(conversationId, null);
    }

    /**
     * Ensures the conversation id.
     *
     * @param conversationId the conversation id value
     * @param userId the user id value
     * @return the operation result
     */
    public String ensureConversationId(String conversationId, String userId) {
        return conversationService.ensureConversationId(conversationId, userId);
    }

    /**
     * Appends the append.
     *
     * @param conversationId the conversation id value
     * @param role the role value
     * @param content the content value
     */
    public void append(String conversationId, String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        conversationService.appendMessage(conversationId, role, content);
    }

    /**
     * Performs the recent operation.
     *
     * @param conversationId the conversation id value
     * @param limit the limit value
     * @return the operation result
     */
    public List<MessageSnapshot> recent(String conversationId, int limit) {
        return conversationService.recentMessages(conversationId, limit).stream()
            .map(this::toSnapshot)
            .toList();
    }

    /**
     * Converts the value to snapshot.
     *
     * @param message the message value
     * @return the converted snapshot
     */
    private MessageSnapshot toSnapshot(Conversation.Message message) {
        long timestamp = message.getTimestamp() == null
            ? System.currentTimeMillis()
            : message.getTimestamp().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new MessageSnapshot(message.getRole(), message.getContent(), timestamp);
    }

    public record MessageSnapshot(String role, String content, long timestamp) {
    }
}
