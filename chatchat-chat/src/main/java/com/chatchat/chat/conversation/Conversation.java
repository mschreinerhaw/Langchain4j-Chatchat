package com.chatchat.chat.conversation;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Conversation data model
 */
@Data
@NoArgsConstructor
public class Conversation {

    private String id;
    private String userId;
    private String title;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<Message> messages;

    private Conversation(ConversationBuilder builder) {
        this.id = builder.id;
        this.userId = builder.userId;
        this.title = builder.title;
        this.status = builder.status;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
        this.messages = builder.messages;
    }

    public static ConversationBuilder builder() {
        return new ConversationBuilder();
    }

    /**
     * Message in conversation
     */
    @Data
    @NoArgsConstructor
    public static class Message {
        private String id;
        private String role; // "user" or "assistant"
        private String content;
        private LocalDateTime timestamp;
        private List<String> toolsUsed; // Tools invoked for this message
        private String sourceKnowledgeBase; // Knowledge base used for retrieval
        private List<Map<String, Object>> sources;
        private List<Map<String, Object>> traces;

        public Message(String id,
                       String role,
                       String content,
                       LocalDateTime timestamp,
                       List<String> toolsUsed,
                       String sourceKnowledgeBase) {
            this(id, role, content, timestamp, toolsUsed, sourceKnowledgeBase, List.of(), List.of());
        }

        public Message(String id,
                       String role,
                       String content,
                       LocalDateTime timestamp,
                       List<String> toolsUsed,
                       String sourceKnowledgeBase,
                       List<Map<String, Object>> sources,
                       List<Map<String, Object>> traces) {
            this.id = id;
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
            this.toolsUsed = toolsUsed;
            this.sourceKnowledgeBase = sourceKnowledgeBase;
            this.sources = sources;
            this.traces = traces;
        }

        private Message(MessageBuilder builder) {
            this.id = builder.id;
            this.role = builder.role;
            this.content = builder.content;
            this.timestamp = builder.timestamp;
            this.toolsUsed = builder.toolsUsed;
            this.sourceKnowledgeBase = builder.sourceKnowledgeBase;
            this.sources = builder.sources;
            this.traces = builder.traces;
        }

        public static MessageBuilder builder() {
            return new MessageBuilder();
        }
    }

    public static class ConversationBuilder {
        private String id;
        private String userId;
        private String title;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private List<Message> messages;

        public ConversationBuilder id(String id) {
            this.id = id;
            return this;
        }

        public ConversationBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public ConversationBuilder title(String title) {
            this.title = title;
            return this;
        }

        public ConversationBuilder status(String status) {
            this.status = status;
            return this;
        }

        public ConversationBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public ConversationBuilder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public ConversationBuilder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }

        public Conversation build() {
            return new Conversation(this);
        }
    }

    public static class MessageBuilder {
        private String id;
        private String role;
        private String content;
        private LocalDateTime timestamp;
        private List<String> toolsUsed;
        private String sourceKnowledgeBase;
        private List<Map<String, Object>> sources;
        private List<Map<String, Object>> traces;

        public MessageBuilder id(String id) {
            this.id = id;
            return this;
        }

        public MessageBuilder role(String role) {
            this.role = role;
            return this;
        }

        public MessageBuilder content(String content) {
            this.content = content;
            return this;
        }

        public MessageBuilder timestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public MessageBuilder toolsUsed(List<String> toolsUsed) {
            this.toolsUsed = toolsUsed;
            return this;
        }

        public MessageBuilder sourceKnowledgeBase(String sourceKnowledgeBase) {
            this.sourceKnowledgeBase = sourceKnowledgeBase;
            return this;
        }

        public MessageBuilder sources(List<Map<String, Object>> sources) {
            this.sources = sources;
            return this;
        }

        public MessageBuilder traces(List<Map<String, Object>> traces) {
            this.traces = traces;
            return this;
        }

        public Message build() {
            return new Message(this);
        }
    }
}
