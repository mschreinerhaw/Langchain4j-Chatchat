package com.chatchat.chat.interaction.service;

import com.chatchat.chat.conversation.Conversation;
import com.chatchat.chat.conversation.ConversationSummary;
import com.chatchat.chat.conversation.ConversationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Persistent conversation memory facade to support interaction orchestration.
 */
@Slf4j
@Service
public class ConversationMemoryService {

    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ConversationContextProperties properties;

    /**
     * Creates a new ConversationMemoryService instance.
     *
     * @param conversationService the conversation service value
     */
    @Autowired
    public ConversationMemoryService(ConversationService conversationService,
                                     ObjectMapper objectMapper,
                                     ObjectProvider<ChatModel> chatModelProvider,
                                     ConversationContextProperties properties) {
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.chatModelProvider = chatModelProvider;
        this.properties = properties == null ? new ConversationContextProperties() : properties;
    }

    public ConversationMemoryService(ConversationService conversationService, ObjectMapper objectMapper) {
        this(conversationService, objectMapper, null, new ConversationContextProperties());
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
        append(conversationId, role, content, List.of(), List.of());
    }

    public void append(String conversationId, String role, String content, Object sources, Object traces) {
        if (content == null || content.isBlank()) {
            return;
        }
        conversationService.appendMessage(conversationId, role, content, toMaps(sources), toMaps(traces));
    }

    private List<Map<String, Object>> toMaps(Object value) {
        if (value == null) {
            return List.of();
        }
        return objectMapper.convertValue(value, new TypeReference<List<Map<String, Object>>>() {
        });
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

    public Optional<ConversationSummary> summary(String conversationId) {
        return conversationService.latestSummary(conversationId);
    }

    public void maybeRefreshSummary(String conversationId) {
        if (conversationId == null || conversationId.isBlank() || !properties.isSummaryEnabled()) {
            return;
        }
        List<Conversation.Message> candidates = conversationService.summaryCandidates(
            conversationId,
            properties.getSummaryKeepRecentMessages()
        );
        if (candidates.size() < properties.getSummaryTriggerMessages()) {
            return;
        }

        String previousSummary = conversationService.latestSummary(conversationId)
            .map(ConversationSummary::summary)
            .orElse("");
        String summary = summarize(previousSummary, candidates);
        if (summary == null || summary.isBlank()) {
            return;
        }
        conversationService.saveSummary(
            conversationId,
            limit(summary, properties.getSummaryMaxChars()),
            candidates.get(0).getId(),
            candidates.get(candidates.size() - 1).getId()
        );
    }

    private String summarize(String previousSummary, List<Conversation.Message> messages) {
        ChatModel chatModel = chatModelProvider == null ? null : chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return fallbackSummary(previousSummary, messages);
        }
        try {
            return chatModel.chat(buildSummaryPrompt(previousSummary, messages));
        } catch (Exception ex) {
            log.warn("Failed to update conversation summary, using local fallback: {}", ex.getMessage());
            return fallbackSummary(previousSummary, messages);
        }
    }

    private String buildSummaryPrompt(String previousSummary, List<Conversation.Message> messages) {
        StringBuilder builder = new StringBuilder();
        builder.append("You maintain compressed conversation context for a multi-turn AI assistant.\n")
            .append("Update the summary using the prior summary and the new transcript. ")
            .append("Keep durable facts, user goals, constraints, unresolved tasks, decisions, and important tool/document findings. ")
            .append("Do not copy the transcript verbatim. Return only the updated summary, within ")
            .append(properties.getSummaryMaxChars())
            .append(" characters.\n\n");
        if (previousSummary != null && !previousSummary.isBlank()) {
            builder.append("Prior summary:\n").append(limit(previousSummary, properties.getSummaryMaxChars())).append("\n\n");
        }
        builder.append("New transcript:\n").append(formatMessages(messages, 900));
        return builder.toString();
    }

    private String fallbackSummary(String previousSummary, List<Conversation.Message> messages) {
        List<String> lines = new ArrayList<>();
        if (previousSummary != null && !previousSummary.isBlank()) {
            lines.add(limit(previousSummary.trim(), Math.max(400, properties.getSummaryMaxChars() / 2)));
        }
        lines.add("Recent condensed context:");
        for (Conversation.Message message : messages) {
            if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            lines.add(formatRole(message.getRole()) + ": " + limit(message.getContent().trim(), 220));
        }
        return limit(String.join("\n", lines), properties.getSummaryMaxChars());
    }

    private String formatMessages(List<Conversation.Message> messages, int contentLimit) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return messages.stream()
            .filter(message -> message != null && message.getContent() != null && !message.getContent().isBlank())
            .map(message -> formatRole(message.getRole()) + ": " + limit(message.getContent().trim(), contentLimit))
            .collect(Collectors.joining("\n"));
    }

    private String formatRole(String role) {
        if (role == null || role.isBlank()) {
            return "unknown";
        }
        return role.trim();
    }

    private String limit(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        int max = Math.max(1, maxChars);
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
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
