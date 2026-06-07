package com.chatchat.api.conversation;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationService {

    private static final String DEFAULT_TENANT_ID = "default";
    private static final String DEFAULT_USER_ID = "anonymous";

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageIndexRepository messageIndexRepository;
    private final ChatMessageDetailStore detailStore;

    public ConversationService(ChatSessionRepository sessionRepository,
                               ChatMessageIndexRepository messageIndexRepository,
                               ChatMessageDetailStore detailStore) {
        this.sessionRepository = sessionRepository;
        this.messageIndexRepository = messageIndexRepository;
        this.detailStore = detailStore;
    }

    @Transactional
    public Conversation createConversation(String userId, String title) {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setTenantId(DEFAULT_TENANT_ID);
        session.setUserId(normalize(userId, DEFAULT_USER_ID));
        session.setTitle(normalizeTitle(title));
        session.setStatus("active");
        return toConversation(sessionRepository.save(session), List.of());
    }

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

    @Transactional(readOnly = true)
    public Optional<Conversation> getConversation(String conversationId) {
        return sessionRepository.findById(conversationId)
            .map(session -> toConversation(session, listMessageDetails(conversationId)));
    }

    @Transactional(readOnly = true)
    public List<Conversation> listUserConversations(String userId) {
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
            .map(session -> toConversation(session, List.of()))
            .toList();
    }

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

    @Transactional
    public void deleteConversation(String conversationId) {
        List<ChatMessageIndexEntity> indexes = messageIndexRepository.findBySessionIdOrderByCreatedAtAsc(conversationId);
        indexes.forEach(index -> detailStore.delete(index.getRocksKey()));
        messageIndexRepository.deleteBySessionId(conversationId);
        sessionRepository.deleteById(conversationId);
    }

    @Transactional
    public Conversation.Message appendMessage(String conversationId, String role, String content) {
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

    private List<Conversation.Message> listMessageDetails(String conversationId) {
        return messageIndexRepository.findBySessionIdOrderByCreatedAtAsc(conversationId).stream()
            .map(index -> detailStore.get(index.getRocksKey()).orElse(null))
            .filter(detail -> detail != null)
            .map(this::toMessage)
            .toList();
    }

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

    private Conversation.Message toMessage(ChatMessageDetail detail) {
        return Conversation.Message.builder()
            .id(detail.getMessageId())
            .role(detail.getRole())
            .content(detail.getContent())
            .timestamp(toLocalDateTime(detail.getCreatedAt()))
            .toolsUsed(detail.getToolsUsed())
            .sourceKnowledgeBase(detail.getSourceKnowledgeBase())
            .build();
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String normalizeTitle(String value) {
        String normalized = normalize(value, "New Conversation");
        return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
    }
}
