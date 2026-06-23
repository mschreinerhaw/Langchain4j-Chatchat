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
    private String skillId;
    private String modelName;
    private String mode;
    private String agentName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Map<String, Object> analysisTree;
    private List<Message> messages;

    /**
     * Creates a new Conversation instance.
     *
     * @param builder the builder value
     */
    private Conversation(ConversationBuilder builder) {
        this.id = builder.id;
        this.userId = builder.userId;
        this.title = builder.title;
        this.status = builder.status;
        this.skillId = builder.skillId;
        this.modelName = builder.modelName;
        this.mode = builder.mode;
        this.agentName = builder.agentName;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
        this.analysisTree = builder.analysisTree;
        this.messages = builder.messages;
    }

    /**
     * Builds the er.
     *
     * @return the built er
     */
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
        private List<Map<String, Object>> steps;
        private Map<String, Object> visualizationSpec;
        private Map<String, Object> uiResponse;
        private List<Map<String, Object>> evidencePremises;
        private Map<String, Object> memoryContext;
        private String agentName;
        private String modelName;
        private String analysisNodeId;
        private String analysisParentNodeId;
        private String analysisSourceMessageId;
        private Map<String, Object> analysisSelection;
        private Boolean streaming;
        private String status;
        private String taskId;

        /**
         * Creates a new Conversation instance.
         *
         * @param id the id value
         * @param role the role value
         * @param content the content value
         * @param timestamp the timestamp value
         * @param toolsUsed the tools used value
         * @param sourceKnowledgeBase the source knowledge base value
         */
        public Message(String id,
                       String role,
                       String content,
                       LocalDateTime timestamp,
                       List<String> toolsUsed,
                       String sourceKnowledgeBase) {
            this(id, role, content, timestamp, toolsUsed, sourceKnowledgeBase, List.of(), List.of());
        }

        /**
         * Creates a new Conversation instance.
         *
         * @param id the id value
         * @param role the role value
         * @param content the content value
         * @param timestamp the timestamp value
         * @param toolsUsed the tools used value
         * @param sourceKnowledgeBase the source knowledge base value
         * @param sources the sources value
         * @param traces the traces value
         */
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

        /**
         * Creates a new Conversation instance.
         *
         * @param builder the builder value
         */
        private Message(MessageBuilder builder) {
            this.id = builder.id;
            this.role = builder.role;
            this.content = builder.content;
            this.timestamp = builder.timestamp;
            this.toolsUsed = builder.toolsUsed;
            this.sourceKnowledgeBase = builder.sourceKnowledgeBase;
            this.sources = builder.sources;
            this.traces = builder.traces;
            this.steps = builder.steps;
            this.visualizationSpec = builder.visualizationSpec;
            this.uiResponse = builder.uiResponse;
            this.evidencePremises = builder.evidencePremises;
            this.memoryContext = builder.memoryContext;
            this.agentName = builder.agentName;
            this.modelName = builder.modelName;
            this.analysisNodeId = builder.analysisNodeId;
            this.analysisParentNodeId = builder.analysisParentNodeId;
            this.analysisSourceMessageId = builder.analysisSourceMessageId;
            this.analysisSelection = builder.analysisSelection;
            this.streaming = builder.streaming;
            this.status = builder.status;
            this.taskId = builder.taskId;
        }

        /**
         * Builds the er.
         *
         * @return the built er
         */
        public static MessageBuilder builder() {
            return new MessageBuilder();
        }
    }

    public static class ConversationBuilder {
        private String id;
        private String userId;
        private String title;
        private String status;
        private String skillId;
        private String modelName;
        private String mode;
        private String agentName;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private Map<String, Object> analysisTree;
        private List<Message> messages;

        /**
         * Performs the id operation.
         *
         * @param id the id value
         * @return the operation result
         */
        public ConversationBuilder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Performs the user id operation.
         *
         * @param userId the user id value
         * @return the operation result
         */
        public ConversationBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Performs the title operation.
         *
         * @param title the title value
         * @return the operation result
         */
        public ConversationBuilder title(String title) {
            this.title = title;
            return this;
        }

        /**
         * Performs the status operation.
         *
         * @param status the status value
         * @return the operation result
         */
        public ConversationBuilder status(String status) {
            this.status = status;
            return this;
        }

        public ConversationBuilder skillId(String skillId) {
            this.skillId = skillId;
            return this;
        }

        public ConversationBuilder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public ConversationBuilder mode(String mode) {
            this.mode = mode;
            return this;
        }

        public ConversationBuilder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        /**
         * Creates the d at.
         *
         * @param createdAt the created at value
         * @return the created d at
         */
        public ConversationBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /**
         * Updates the d at.
         *
         * @param updatedAt the updated at value
         * @return the updated d at
         */
        public ConversationBuilder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public ConversationBuilder analysisTree(Map<String, Object> analysisTree) {
            this.analysisTree = analysisTree;
            return this;
        }

        /**
         * Performs the messages operation.
         *
         * @param messages the messages value
         * @return the operation result
         */
        public ConversationBuilder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }

        /**
         * Builds the build.
         *
         * @return the built build
         */
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
        private List<Map<String, Object>> steps;
        private Map<String, Object> visualizationSpec;
        private Map<String, Object> uiResponse;
        private List<Map<String, Object>> evidencePremises;
        private Map<String, Object> memoryContext;
        private String agentName;
        private String modelName;
        private String analysisNodeId;
        private String analysisParentNodeId;
        private String analysisSourceMessageId;
        private Map<String, Object> analysisSelection;
        private Boolean streaming;
        private String status;
        private String taskId;

        /**
         * Performs the id operation.
         *
         * @param id the id value
         * @return the operation result
         */
        public MessageBuilder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Performs the role operation.
         *
         * @param role the role value
         * @return the operation result
         */
        public MessageBuilder role(String role) {
            this.role = role;
            return this;
        }

        /**
         * Performs the content operation.
         *
         * @param content the content value
         * @return the operation result
         */
        public MessageBuilder content(String content) {
            this.content = content;
            return this;
        }

        /**
         * Performs the timestamp operation.
         *
         * @param timestamp the timestamp value
         * @return the operation result
         */
        public MessageBuilder timestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Converts the value to ols used.
         *
         * @param toolsUsed the tools used value
         * @return the converted ols used
         */
        public MessageBuilder toolsUsed(List<String> toolsUsed) {
            this.toolsUsed = toolsUsed;
            return this;
        }

        /**
         * Performs the source knowledge base operation.
         *
         * @param sourceKnowledgeBase the source knowledge base value
         * @return the operation result
         */
        public MessageBuilder sourceKnowledgeBase(String sourceKnowledgeBase) {
            this.sourceKnowledgeBase = sourceKnowledgeBase;
            return this;
        }

        /**
         * Performs the sources operation.
         *
         * @param sources the sources value
         * @return the operation result
         */
        public MessageBuilder sources(List<Map<String, Object>> sources) {
            this.sources = sources;
            return this;
        }

        /**
         * Performs the traces operation.
         *
         * @param traces the traces value
         * @return the operation result
         */
        public MessageBuilder traces(List<Map<String, Object>> traces) {
            this.traces = traces;
            return this;
        }

        public MessageBuilder steps(List<Map<String, Object>> steps) {
            this.steps = steps;
            return this;
        }

        public MessageBuilder visualizationSpec(Map<String, Object> visualizationSpec) {
            this.visualizationSpec = visualizationSpec;
            return this;
        }

        public MessageBuilder uiResponse(Map<String, Object> uiResponse) {
            this.uiResponse = uiResponse;
            return this;
        }

        public MessageBuilder evidencePremises(List<Map<String, Object>> evidencePremises) {
            this.evidencePremises = evidencePremises;
            return this;
        }

        public MessageBuilder memoryContext(Map<String, Object> memoryContext) {
            this.memoryContext = memoryContext;
            return this;
        }

        public MessageBuilder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public MessageBuilder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public MessageBuilder analysisNodeId(String analysisNodeId) {
            this.analysisNodeId = analysisNodeId;
            return this;
        }

        public MessageBuilder analysisParentNodeId(String analysisParentNodeId) {
            this.analysisParentNodeId = analysisParentNodeId;
            return this;
        }

        public MessageBuilder analysisSourceMessageId(String analysisSourceMessageId) {
            this.analysisSourceMessageId = analysisSourceMessageId;
            return this;
        }

        public MessageBuilder analysisSelection(Map<String, Object> analysisSelection) {
            this.analysisSelection = analysisSelection;
            return this;
        }

        public MessageBuilder streaming(Boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        public MessageBuilder status(String status) {
            this.status = status;
            return this;
        }

        public MessageBuilder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        /**
         * Builds the build.
         *
         * @return the built build
         */
        public Message build() {
            return new Message(this);
        }
    }
}
