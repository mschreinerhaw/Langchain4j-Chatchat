package com.chatchat.chat.conversation;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationService {

    private static final String DEFAULT_TENANT_ID = "default";
    private static final String DEFAULT_USER_ID = "anonymous";

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageIndexRepository messageIndexRepository;
    private final ChatMessageDetailStore detailStore;

    /**
     * Creates a new ConversationService instance.
     *
     * @param sessionRepository the session repository value
     * @param messageIndexRepository the message index repository value
     * @param detailStore the detail store value
     */
    public ConversationService(ChatSessionRepository sessionRepository,
                               ChatMessageIndexRepository messageIndexRepository,
                               ChatMessageDetailStore detailStore) {
        this.sessionRepository = sessionRepository;
        this.messageIndexRepository = messageIndexRepository;
        this.detailStore = detailStore;
    }

    /**
     * Creates the conversation.
     *
     * @param userId the user id value
     * @param title the title value
     * @return the created conversation
     */
    @Transactional
    public Conversation createConversation(String userId, String title) {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setTenantId(DEFAULT_TENANT_ID);
        session.setUserId(normalize(userId, DEFAULT_USER_ID));
        session.setTitle(normalizeTitle(title));
        session.setStatus("active");
        return toConversation(sessionRepository.save(session), List.of());
    }

    /**
     * Ensures the conversation id.
     *
     * @param conversationId the conversation id value
     * @param userId the user id value
     * @return the operation result
     */
    @Transactional
    public String ensureConversationId(String conversationId, String userId) {
        if (conversationId == null || conversationId.isBlank()) {
            return createConversation(userId, "New Conversation").getId();
        }
        String normalizedConversationId = conversationId.trim();
        if (sessionRepository.existsById(normalizedConversationId)) {
            return normalizedConversationId;
        }
        ChatSessionEntity session = new ChatSessionEntity();
        session.setSessionId(normalizedConversationId);
        session.setTenantId(DEFAULT_TENANT_ID);
        session.setUserId(normalize(userId, DEFAULT_USER_ID));
        session.setTitle("New Conversation");
        session.setStatus("active");
        sessionRepository.save(session);
        return normalizedConversationId;
    }

    /**
     * Returns the conversation.
     *
     * @param conversationId the conversation id value
     * @return the conversation
     */
    @Transactional(readOnly = true)
    public Optional<Conversation> getConversation(String conversationId) {
        return sessionRepository.findById(conversationId)
            .map(session -> toConversation(session, listMessageDetails(conversationId)));
    }

    /**
     * Lists the user conversations.
     *
     * @param userId the user id value
     * @return the user conversations list
     */
    @Transactional(readOnly = true)
    public List<Conversation> listUserConversations(String userId) {
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
            .map(session -> toConversation(session, List.of()))
            .toList();
    }

    /**
     * Updates the conversation summary.
     *
     * @param conversationId the conversation id value
     * @param userId the user id value
     * @param title the title value
     * @param status the status value
     * @return the updated conversation summary
     */
    @Transactional
    public Conversation updateConversationSummary(String conversationId, String userId, String title, String status) {
        String normalizedConversationId = ensureConversationId(conversationId, userId);
        ChatSessionEntity session = sessionRepository.findById(normalizedConversationId)
            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + normalizedConversationId));
        if (title != null && !title.isBlank()) {
            session.setTitle(normalizeTitle(title));
        }
        if (status != null && !status.isBlank()) {
            session.setStatus(status.trim());
        }
        return toConversation(sessionRepository.save(session), listMessageDetails(normalizedConversationId));
    }

    /**
     * Deletes the conversation.
     *
     * @param conversationId the conversation id value
     */
    @Transactional
    public void deleteConversation(String conversationId) {
        List<ChatMessageIndexEntity> indexes = messageIndexRepository.findBySessionIdOrderByCreatedAtAsc(conversationId);
        indexes.forEach(index -> detailStore.delete(index.getRocksKey()));
        messageIndexRepository.deleteBySessionId(conversationId);
        sessionRepository.deleteById(conversationId);
    }

    /**
     * Performs the replace messages operation.
     *
     * @param conversationId the conversation id value
     * @param userId the user id value
     * @param messages the messages value
     */
    @Transactional
    public void replaceMessages(String conversationId, String userId, List<Conversation.Message> messages) {
        String normalizedConversationId = ensureConversationId(conversationId, userId);
        ChatSessionEntity session = sessionRepository.findById(normalizedConversationId)
            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + normalizedConversationId));

        List<ChatMessageIndexEntity> existing = messageIndexRepository.findBySessionIdOrderByCreatedAtAsc(normalizedConversationId);
        existing.forEach(index -> detailStore.delete(index.getRocksKey()));
        messageIndexRepository.deleteBySessionId(normalizedConversationId);

        Instant lastCreatedAt = session.getUpdatedAt() == null ? Instant.now() : session.getUpdatedAt();
        List<Conversation.Message> snapshot = messages == null ? List.of() : messages;
        for (int index = 0; index < snapshot.size(); index++) {
            Conversation.Message message = snapshot.get(index);
            if (message == null || message.getRole() == null || message.getRole().isBlank()) {
                continue;
            }
            Instant createdAt = toInstant(message.getTimestamp(), index);
            lastCreatedAt = createdAt;
            saveMessageDetail(session, message, createdAt);
        }

        session.setUpdatedAt(lastCreatedAt);
        sessionRepository.save(session);
    }

    /**
     * Appends the message.
     *
     * @param conversationId the conversation id value
     * @param role the role value
     * @param content the content value
     * @return the operation result
     */
    @Transactional
    public Conversation.Message appendMessage(String conversationId, String role, String content) {
        return appendMessage(conversationId, role, content, List.of(), List.of());
    }

    @Transactional
    public Conversation.Message appendMessage(String conversationId,
                                              String role,
                                              String content,
                                              List<Map<String, Object>> sources,
                                              List<Map<String, Object>> traces) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Message content cannot be empty");
        }
        ChatSessionEntity session = sessionRepository.findById(conversationId)
            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        Instant createdAt = Instant.now();
        String messageId = UUID.randomUUID().toString();
        ChatMessageDetail detail = ChatMessageDetail.builder()
            .messageId(messageId)
            .sessionId(session.getSessionId())
            .tenantId(session.getTenantId())
            .userId(session.getUserId())
            .role(normalize(role, "user"))
            .content(content)
            .createdAt(createdAt)
            .sources(copyMaps(sources))
            .traces(copyMaps(traces))
            .build();
        String rocksKey = detailStore.put(detail);

        ChatMessageIndexEntity index = new ChatMessageIndexEntity();
        index.setMessageId(messageId);
        index.setSessionId(session.getSessionId());
        index.setTenantId(session.getTenantId());
        index.setUserId(session.getUserId());
        index.setRole(detail.getRole());
        index.setCreatedAt(createdAt);
        index.setRocksKey(rocksKey);
        messageIndexRepository.save(index);

        session.setUpdatedAt(createdAt);
        sessionRepository.save(session);
        return toMessage(detail);
    }

    /**
     * Performs the recent messages operation.
     *
     * @param conversationId the conversation id value
     * @param limit the limit value
     * @return the operation result
     */
    @Transactional(readOnly = true)
    public List<Conversation.Message> recentMessages(String conversationId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return messageIndexRepository.findBySessionIdOrderByCreatedAtDesc(conversationId, PageRequest.of(0, limit)).stream()
            .sorted(Comparator.comparing(ChatMessageIndexEntity::getCreatedAt))
            .map(index -> detailStore.get(index.getRocksKey()).orElse(null))
            .filter(detail -> detail != null)
            .map(this::toMessage)
            .toList();
    }

    /**
     * Lists the message details.
     *
     * @param conversationId the conversation id value
     * @return the message details list
     */
    private List<Conversation.Message> listMessageDetails(String conversationId) {
        return messageIndexRepository.findBySessionIdOrderByCreatedAtAsc(conversationId).stream()
            .map(index -> detailStore.get(index.getRocksKey()).orElse(null))
            .filter(detail -> detail != null)
            .map(this::toMessage)
            .toList();
    }

    /**
     * Converts the value to conversation.
     *
     * @param session the session value
     * @param messages the messages value
     * @return the converted conversation
     */
    private Conversation toConversation(ChatSessionEntity session, List<Conversation.Message> messages) {
        return Conversation.builder()
            .id(session.getSessionId())
            .userId(session.getUserId())
            .title(session.getTitle())
            .status(session.getStatus())
            .createdAt(toLocalDateTime(session.getCreatedAt()))
            .updatedAt(toLocalDateTime(session.getUpdatedAt()))
            .messages(messages)
            .build();
    }

    /**
     * Converts the value to message.
     *
     * @param detail the detail value
     * @return the converted message
     */
    private Conversation.Message toMessage(ChatMessageDetail detail) {
        return Conversation.Message.builder()
            .id(detail.getMessageId())
            .role(detail.getRole())
            .content(detail.getContent())
            .timestamp(toLocalDateTime(detail.getCreatedAt()))
            .toolsUsed(detail.getToolsUsed())
            .sourceKnowledgeBase(detail.getSourceKnowledgeBase())
            .sources(copyMaps(detail.getSources()))
            .traces(copyMaps(detail.getTraces()))
            .build();
    }

    /**
     * Saves the message detail.
     *
     * @param session the session value
     * @param message the message value
     * @param createdAt the created at value
     */
    private void saveMessageDetail(ChatSessionEntity session, Conversation.Message message, Instant createdAt) {
        String messageId = message.getId() == null || message.getId().isBlank()
            ? UUID.randomUUID().toString()
            : message.getId().trim();
        ChatMessageDetail detail = ChatMessageDetail.builder()
            .messageId(messageId)
            .sessionId(session.getSessionId())
            .tenantId(session.getTenantId())
            .userId(session.getUserId())
            .role(normalize(message.getRole(), "user"))
            .content(message.getContent() == null ? "" : message.getContent())
            .createdAt(createdAt)
            .toolsUsed(message.getToolsUsed())
            .sourceKnowledgeBase(message.getSourceKnowledgeBase())
            .sources(copyMaps(message.getSources()))
            .traces(copyMaps(message.getTraces()))
            .build();
        String rocksKey = detailStore.put(detail);

        ChatMessageIndexEntity index = new ChatMessageIndexEntity();
        index.setMessageId(messageId);
        index.setSessionId(session.getSessionId());
        index.setTenantId(session.getTenantId());
        index.setUserId(session.getUserId());
        index.setRole(detail.getRole());
        index.setCreatedAt(createdAt);
        index.setRocksKey(rocksKey);
        messageIndexRepository.save(index);
    }

    /**
     * Converts the value to local date time.
     *
     * @param instant the instant value
     * @return the converted local date time
     */
    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    /**
     * Converts the value to instant.
     *
     * @param value the value value
     * @param offset the offset value
     * @return the converted instant
     */
    private Instant toInstant(LocalDateTime value, int offset) {
        if (value != null) {
            return value.atZone(ZoneId.systemDefault()).toInstant();
        }
        return Instant.now().plusMillis(Math.max(0, offset));
    }

    /**
     * Copies the maps.
     *
     * @param values the values value
     * @return the operation result
     */
    private List<Map<String, Object>> copyMaps(List<Map<String, Object>> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> value : values) {
            if (value != null && !value.isEmpty()) {
                copy.add(new LinkedHashMap<>(value));
            }
        }
        return copy;
    }

    /**
     * Normalizes the normalize.
     *
     * @param value the value value
     * @param fallback the fallback value
     * @return the operation result
     */
    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    /**
     * Normalizes the title.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizeTitle(String value) {
        String normalized = normalize(value, "New Conversation");
        return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
    }
}
