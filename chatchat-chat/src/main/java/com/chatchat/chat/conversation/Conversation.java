package com.chatchat.chat.conversation;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Conversation data model
 */
@Data
@Builder
public class Conversation {

    private String id;
    private String userId;
    private String title;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<Message> messages;

    /**
     * Message in conversation
     */
    @Data
    @Builder
    public static class Message {
        private String id;
        private String role; // "user" or "assistant"
        private String content;
        private LocalDateTime timestamp;
        private List<String> toolsUsed; // Tools invoked for this message
        private String sourceKnowledgeBase; // Knowledge base used for retrieval
    }
}
