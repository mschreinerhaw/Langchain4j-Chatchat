package com.chatchat.chat.interaction.service;

import com.chatchat.chat.conversation.Conversation;
import com.chatchat.chat.conversation.ConversationSummary;
import com.chatchat.chat.conversation.ConversationService;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chatchat.common.interaction.InteractionToolTrace;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    public String ensureConversationId(String tenantId, String conversationId, String userId) {
        return conversationService.ensureConversationId(tenantId, conversationId, userId);
    }

    /**
     * Appends the append.
     *
     * @param conversationId the conversation id value
     * @param role the role value
     * @param content the content value
     */
    public void append(String conversationId, String role, String content) {
        append(conversationId, role, content, List.of(), List.of(), Map.of());
    }

    public void append(String conversationId, String role, String content, Object sources, Object traces) {
        append(conversationId, role, content, sources, traces, Map.of());
    }

    public void append(String conversationId, String role, String content, Object sources, Object traces, Object memoryContext) {
        if (content == null || content.isBlank()) {
            return;
        }
        conversationService.appendMessage(conversationId, role, content, toMaps(sources), toMaps(traces), toMap(memoryContext));
    }

    private List<Map<String, Object>> toMaps(Object value) {
        if (value == null) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(value, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
    }

    private Map<String, Object> toMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        try {
            Map<String, Object> map = objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {
            });
            return map == null ? Map.of() : map;
        } catch (IllegalArgumentException ex) {
            return Map.of();
        }
    }

    public Map<String, Object> responseMemoryContext(InteractionResponse response) {
        if (response == null) {
            return Map.of();
        }
        Map<String, Object> metadata = safeMap(response.getMetadata());
        Map<String, Object> agent = safeMap(metadata.get("agent"));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("contractVersion", "conversation_memory_context_v1");
        copyIfPresent(context, metadata, "handler");
        copyIfPresent(context, metadata, "skillId");
        copyIfPresent(context, metadata, "modelName");
        copyIfPresent(context, agent, "groundingStatus");
        copyIfPresent(context, agent, "answerDecision");
        copyIfPresent(context, agent, "answerDecisionReason");
        copyIfPresent(context, agent, "answerRewriteSource");
        copyIfPresent(context, agent, "answerQualitySelectedId");
        copyIfPresent(context, agent, "answerQualitySelectedSource");

        List<Map<String, Object>> sources = limitMaps(toMaps(response.getSources()), 8);
        if (!sources.isEmpty()) {
            context.put("sources", sources);
        }
        List<Map<String, Object>> citations = citations(agent, sources);
        if (!citations.isEmpty()) {
            context.put("citations", citations);
        }
        Map<String, Object> evidenceAnswer = compactEvidenceAnswer(safeMap(agent.get("evidenceAnswer")));
        if (!evidenceAnswer.isEmpty()) {
            context.put("evidenceAnswer", evidenceAnswer);
        }
        List<Map<String, Object>> tools = compactTools(response.getToolTraces());
        if (!tools.isEmpty()) {
            context.put("tools", tools);
        }
        return context.size() <= 1 ? Map.of() : Map.copyOf(context);
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

    public List<MessageSnapshot> recent(String tenantId, String conversationId, int limit) {
        return conversationService.recentMessages(tenantId, conversationId, limit).stream()
            .map(this::toSnapshot)
            .toList();
    }

    public Optional<ConversationSummary> summary(String conversationId) {
        return conversationService.latestSummary(conversationId);
    }

    public Optional<ConversationSummary> summary(String tenantId, String conversationId) {
        return conversationService.latestSummary(tenantId, conversationId);
    }

    public void maybeRefreshSummary(String conversationId) {
        maybeRefreshSummary(null, conversationId);
    }

    public void maybeRefreshSummary(String tenantId, String conversationId) {
        if (conversationId == null || conversationId.isBlank() || !properties.isSummaryEnabled()) {
            return;
        }
        List<Conversation.Message> candidates = tenantId == null || tenantId.isBlank()
            ? conversationService.summaryCandidates(conversationId, properties.getSummaryKeepRecentMessages())
            : conversationService.summaryCandidates(tenantId, conversationId, properties.getSummaryKeepRecentMessages());
        if (candidates.size() < properties.getSummaryTriggerMessages()) {
            return;
        }

        String previousSummary = (tenantId == null || tenantId.isBlank()
            ? conversationService.latestSummary(conversationId)
            : conversationService.latestSummary(tenantId, conversationId))
            .map(ConversationSummary::summary)
            .orElse("");
        String summary = summarize(previousSummary, candidates);
        if (summary == null || summary.isBlank()) {
            return;
        }
        if (tenantId == null || tenantId.isBlank()) {
            conversationService.saveSummary(
                conversationId,
                limit(summary, properties.getSummaryMaxChars()),
                candidates.get(0).getId(),
                candidates.get(candidates.size() - 1).getId()
            );
        } else {
            conversationService.saveSummary(
                tenantId,
                conversationId,
                limit(summary, properties.getSummaryMaxChars()),
                candidates.get(0).getId(),
                candidates.get(candidates.size() - 1).getId()
            );
        }
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
            String memory = memoryContextText(message.getMemoryContext());
            if (!memory.isBlank()) {
                lines.add("  context: " + limit(memory, 320));
            }
        }
        return limit(String.join("\n", lines), properties.getSummaryMaxChars());
    }

    private String formatMessages(List<Conversation.Message> messages, int contentLimit) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return messages.stream()
            .filter(message -> message != null && message.getContent() != null && !message.getContent().isBlank())
            .map(message -> {
                String line = formatRole(message.getRole()) + ": " + limit(message.getContent().trim(), contentLimit);
                String memory = memoryContextText(message.getMemoryContext());
                return memory.isBlank() ? line : line + "\n  context: " + limit(memory, 420);
            })
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
        return new MessageSnapshot(message.getRole(), message.getContent(), timestamp, toMap(message.getMemoryContext()));
    }

    private Map<String, Object> safeMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null && item != null) {
                copy.put(String.valueOf(key), item);
            }
        });
        return copy;
    }

    private void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (target == null || source == null || key == null) {
            return;
        }
        Object value = source.get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    private List<Map<String, Object>> citations(Map<String, Object> agent, List<Map<String, Object>> sources) {
        List<Map<String, Object>> citations = new ArrayList<>();
        citations.addAll(limitMaps(toMaps(firstNonNull(
            nested(agent, "evidenceAnswer", "citations"),
            agent.get("availableEvidenceCitations"),
            agent.get("evidenceForcedCitations")
        )), 8));
        if (citations.isEmpty() && sources != null) {
            citations.addAll(limitMaps(sources, 8));
        }
        return List.copyOf(citations);
    }

    private Map<String, Object> compactEvidenceAnswer(Map<String, Object> evidenceAnswer) {
        if (evidenceAnswer.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        copyIfPresent(compact, evidenceAnswer, "confidence");
        copyIfPresent(compact, evidenceAnswer, "groundingStatus");
        Object answer = evidenceAnswer.get("answer");
        if (answer != null && !String.valueOf(answer).isBlank()) {
            compact.put("summary", limit(String.valueOf(answer).replaceAll("\\s+", " ").trim(), 500));
        }
        List<Map<String, Object>> citations = limitMaps(toMaps(evidenceAnswer.get("citations")), 8);
        if (!citations.isEmpty()) {
            compact.put("citations", citations);
        }
        return Map.copyOf(compact);
    }

    private List<Map<String, Object>> compactTools(List<InteractionToolTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return List.of();
        }
        return traces.stream()
            .limit(8)
            .map(trace -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tool", trace.getToolName());
                item.put("success", trace.isSuccess());
                if (trace.getErrorMessage() != null && !trace.getErrorMessage().isBlank()) {
                    item.put("error", limit(trace.getErrorMessage(), 180));
                }
                return item;
            })
            .toList();
    }

    private List<Map<String, Object>> limitMaps(List<Map<String, Object>> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isEmpty())
            .limit(Math.max(1, limit))
            .map(value -> {
                Map<String, Object> compact = new LinkedHashMap<>();
                copyIfPresent(compact, value, "refId");
                copyIfPresent(compact, value, "sourceRef");
                copyIfPresent(compact, value, "source");
                copyIfPresent(compact, value, "section");
                copyIfPresent(compact, value, "fileId");
                copyIfPresent(compact, value, "rank");
                copyIfPresent(compact, value, "snippet");
                copyIfPresent(compact, value, "text");
                return compact.isEmpty() ? new LinkedHashMap<>(value) : compact;
            })
            .toList();
    }

    private Object nested(Map<String, Object> map, String parent, String child) {
        Object value = map == null ? null : map.get(parent);
        if (value instanceof Map<?, ?> nested) {
            return nested.get(child);
        }
        return null;
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public String memoryContextText(Map<String, Object> memoryContext) {
        if (memoryContext == null || memoryContext.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        addPart(parts, "grounding", memoryContext.get("groundingStatus"));
        addPart(parts, "decision", memoryContext.get("answerDecision"));
        addPart(parts, "rewrite", memoryContext.get("answerRewriteSource"));
        addPart(parts, "qualityWinner", memoryContext.get("answerQualitySelectedId"));
        String citations = citationRefs(memoryContext.get("citations"));
        if (!citations.isBlank()) {
            parts.add("citations=" + citations);
        }
        String tools = toolNames(memoryContext.get("tools"));
        if (!tools.isBlank()) {
            parts.add("tools=" + tools);
        }
        Object evidenceAnswer = nested(memoryContext, "evidenceAnswer", "summary");
        if (evidenceAnswer != null && !String.valueOf(evidenceAnswer).isBlank()) {
            parts.add("evidenceSummary=" + limit(String.valueOf(evidenceAnswer), 260));
        }
        return String.join("; ", parts);
    }

    private void addPart(List<String> parts, String label, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            parts.add(label + "=" + value);
        }
    }

    private String citationRefs(Object value) {
        List<Map<String, Object>> values = toMaps(value);
        return values.stream()
            .map(item -> firstNonBlank(
                stringValue(item.get("refId")),
                firstNonBlank(stringValue(item.get("sourceRef")), stringValue(item.get("source")))
            ))
            .filter(ref -> ref != null && !ref.isBlank())
            .distinct()
            .limit(6)
            .collect(Collectors.joining(", "));
    }

    private String toolNames(Object value) {
        List<Map<String, Object>> values = toMaps(value);
        return values.stream()
            .map(item -> stringValue(item.get("tool")))
            .filter(tool -> tool != null && !tool.isBlank())
            .distinct()
            .limit(6)
            .collect(Collectors.joining(", "));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    public record MessageSnapshot(String role, String content, long timestamp, Map<String, Object> memoryContext) {
        public MessageSnapshot(String role, String content, long timestamp) {
            this(role, content, timestamp, Map.of());
        }

        public String compactContext() {
            if (memoryContext == null || memoryContext.isEmpty()) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            add(parts, "grounding", memoryContext.get("groundingStatus"));
            add(parts, "decision", memoryContext.get("answerDecision"));
            add(parts, "rewrite", memoryContext.get("answerRewriteSource"));
            add(parts, "qualityWinner", memoryContext.get("answerQualitySelectedId"));
            String citations = refs(memoryContext.get("citations"));
            if (!citations.isBlank()) {
                parts.add("citations=" + citations);
            }
            String tools = tools(memoryContext.get("tools"));
            if (!tools.isBlank()) {
                parts.add("tools=" + tools);
            }
            return String.join("; ", parts);
        }

        private static void add(List<String> parts, String label, Object value) {
            if (value != null && !String.valueOf(value).isBlank()) {
                parts.add(label + "=" + value);
            }
        }

        private static String refs(Object value) {
            if (!(value instanceof List<?> list)) {
                return "";
            }
            return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> first(
                    string(item.get("refId")),
                    first(string(item.get("sourceRef")), string(item.get("source")))
                ))
                .filter(ref -> ref != null && !ref.isBlank())
                .distinct()
                .limit(6)
                .collect(Collectors.joining(", "));
        }

        private static String tools(Object value) {
            if (!(value instanceof List<?> list)) {
                return "";
            }
            return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> string(item.get("tool")))
                .filter(tool -> tool != null && !tool.isBlank())
                .distinct()
                .limit(6)
                .collect(Collectors.joining(", "));
        }

        private static String string(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private static String first(String first, String second) {
            return first == null || first.isBlank() ? second : first;
        }
    }
}
