package com.chatchat.chat.conversation.service;

import com.chatchat.chat.conversation.store.ChatMessageDetailStore;

import com.chatchat.chat.conversation.persistence.ChatMessageIndexRepository;

import com.chatchat.chat.conversation.persistence.ChatMessageIndexEntity;

import com.chatchat.chat.conversation.persistence.ConversationSummaryRepository;

import com.chatchat.chat.conversation.persistence.ConversationSummaryEntity;

import com.chatchat.chat.conversation.persistence.ChatSessionRepository;

import com.chatchat.chat.conversation.persistence.ChatSessionEntity;

import com.chatchat.chat.conversation.model.ChatMessageDetail;

import com.chatchat.chat.conversation.model.ConversationSummary;

import com.chatchat.chat.conversation.model.Conversation;

import com.chatchat.chat.presentation.UserFacingContentSanitizer;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ConversationService {

    private static final String DEFAULT_TENANT_ID = "default";
    private static final String DEFAULT_USER_ID = "anonymous";
    private static final Set<String> IN_PROGRESS_STATUSES = Set.of("running", "streaming", "pending");

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageIndexRepository messageIndexRepository;
    private final ConversationSummaryRepository summaryRepository;
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
                               ConversationSummaryRepository summaryRepository,
                               ChatMessageDetailStore detailStore) {
        this.sessionRepository = sessionRepository;
        this.messageIndexRepository = messageIndexRepository;
        this.summaryRepository = summaryRepository;
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
        return createConversation(DEFAULT_TENANT_ID, userId, title);
    }

    @Transactional
    public Conversation createConversation(String tenantId, String userId, String title) {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setTenantId(normalizeTenantId(tenantId));
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
        return ensureConversationId(DEFAULT_TENANT_ID, conversationId, userId);
    }

    @Transactional
    public String ensureConversationId(String tenantId, String conversationId, String userId) {
        String normalizedTenantId = normalizeTenantId(tenantId);
        if (conversationId == null || conversationId.isBlank()) {
            return createConversation(normalizedTenantId, userId, "New Conversation").getId();
        }
        String normalizedConversationId = conversationId.trim();
        Optional<ChatSessionEntity> existing = sessionRepository.findById(normalizedConversationId);
        if (existing.isPresent()) {
            ensureTenant(existing.get(), normalizedTenantId);
            return normalizedConversationId;
        }
        ChatSessionEntity session = new ChatSessionEntity();
        session.setSessionId(normalizedConversationId);
        session.setTenantId(normalizedTenantId);
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

    @Transactional(readOnly = true)
    public Optional<Conversation> getConversation(String tenantId, String conversationId) {
        String normalizedTenantId = normalizeTenantId(tenantId);
        return sessionRepository.findBySessionIdAndTenantId(conversationId, normalizedTenantId)
            .map(session -> toConversation(session, listMessageDetails(normalizedTenantId, conversationId)));
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

    @Transactional(readOnly = true)
    public List<Conversation> listUserConversations(String tenantId, String userId) {
        return sessionRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(normalizeTenantId(tenantId), normalize(userId, DEFAULT_USER_ID)).stream()
            .map(session -> toConversation(session, List.of()))
            .toList();
    }

    /**
     * Lists only the newest conversation index rows. This path never reads message
     * indexes, RocksDB references, or OpenSearch message documents.
     */
    @Transactional(readOnly = true)
    public List<Conversation> listUserConversationSummaries(String tenantId, String userId, int limit) {
        return listUserConversationSummaries(tenantId, userId, 0, limit);
    }

    @Transactional(readOnly = true)
    public List<Conversation> listUserConversationSummaries(String tenantId, String userId, int page, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 100));
        int normalizedPage = Math.max(0, page);
        return sessionRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(
                normalizeTenantId(tenantId),
                normalize(userId, DEFAULT_USER_ID),
                PageRequest.of(normalizedPage, normalizedLimit)
            ).stream()
            .map(session -> toConversation(session, List.of()))
            .toList();
    }

    /**
     * Returns a searchable conversation page without hydrating message bodies.
     */
    @Transactional(readOnly = true)
    public ConversationPage listUserConversationSummaryPage(String tenantId,
                                                             String userId,
                                                             String keyword,
                                                             int page,
                                                             int pageSize) {
        int normalizedPage = Math.max(1, page);
        int normalizedPageSize = Math.max(1, Math.min(pageSize, 100));
        String normalizedTenantId = normalizeTenantId(tenantId);
        String normalizedUserId = normalize(userId, DEFAULT_USER_ID);
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        PageRequest pageable = PageRequest.of(normalizedPage - 1, normalizedPageSize);
        Page<ChatSessionEntity> result = normalizedKeyword.isBlank()
            ? sessionRepository.findPageByTenantIdAndUserIdOrderByUpdatedAtDesc(
                normalizedTenantId, normalizedUserId, pageable
            )
            : sessionRepository.findPageByTenantIdAndUserIdAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
                normalizedTenantId, normalizedUserId, normalizedKeyword, pageable
            );
        return new ConversationPage(
            result.getContent().stream().map(session -> toConversation(session, List.of())).toList(),
            result.getTotalElements(),
            normalizedPage,
            normalizedPageSize,
            result.getTotalPages()
        );
    }

    public record ConversationPage(
        List<Conversation> items,
        long total,
        int page,
        int pageSize,
        int totalPages
    ) {
    }

    @Transactional
    public Conversation renameConversation(String tenantId, String conversationId, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Conversation title is required");
        }
        String normalizedTenantId = normalizeTenantId(tenantId);
        ChatSessionEntity session = sessionRepository
            .findBySessionIdAndTenantId(conversationId, normalizedTenantId)
            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        session.setTitle(normalizeTitle(title));
        return toConversation(sessionRepository.save(session), List.of());
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
        return updateConversationSummary(conversationId, userId, title, status, null, null, null, null);
    }

    @Transactional
    public Conversation updateConversationSummary(String tenantId,
                                                  String conversationId,
                                                  String userId,
                                                  String title,
                                                  String status) {
        return updateConversationSummary(tenantId, conversationId, userId, title, status, null, null, null, null);
    }

    @Transactional
    public Conversation updateConversationSummary(String conversationId,
                                                  String userId,
                                                  String title,
                                                  String status,
                                                  String skillId,
                                                  String modelName,
                                                  String mode,
                                                  String agentName) {
        return updateConversationSummary(DEFAULT_TENANT_ID, conversationId, userId, title, status, skillId, modelName, mode, agentName);
    }

    @Transactional
    public Conversation updateConversationSummary(String tenantId,
                                                  String conversationId,
                                                  String userId,
                                                  String title,
                                                  String status,
                                                  String skillId,
                                                  String modelName,
                                                  String mode,
                                                  String agentName) {
        String normalizedTenantId = normalizeTenantId(tenantId);
        String normalizedConversationId = ensureConversationId(normalizedTenantId, conversationId, userId);
        ChatSessionEntity session = sessionRepository.findById(normalizedConversationId)
            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + normalizedConversationId));
        ensureTenant(session, normalizedTenantId);
        if (title != null && !title.isBlank()) {
            session.setTitle(normalizeTitle(title));
        }
        if (status != null && !status.isBlank()) {
            session.setStatus(status.trim());
        }
        if (skillId != null) {
            session.setSkillId(blankToNull(skillId));
        }
        if (modelName != null) {
            session.setModelName(blankToNull(modelName));
        }
        if (mode != null) {
            session.setMode(blankToNull(mode));
        }
        if (agentName != null) {
            session.setAgentName(blankToNull(agentName));
        }
        return toConversation(sessionRepository.save(session), listMessageDetails(normalizedTenantId, normalizedConversationId));
    }

    /**
     * Deletes the conversation.
     *
     * @param conversationId the conversation id value
     */
    @Transactional
    public void deleteConversation(String conversationId) {
        ChatSessionEntity session = sessionRepository.findLockedBySessionId(conversationId)
            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        ensureConversationCanBeDeleted(session);
        List<ChatMessageIndexEntity> indexes = messageIndexRepository.findBySessionIdOrderByCreatedAtAsc(conversationId);
        indexes.forEach(index -> detailStore.delete(index.getRocksKey()));
        messageIndexRepository.deleteBySessionId(conversationId);
        summaryRepository.deleteBySessionId(conversationId);
        sessionRepository.delete(session);
    }

    @Transactional
    public void deleteConversation(String tenantId, String conversationId) {
        String normalizedTenantId = normalizeTenantId(tenantId);
        ChatSessionEntity session = sessionRepository.findLockedBySessionIdAndTenantId(conversationId, normalizedTenantId)
            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        ensureConversationCanBeDeleted(session);
        List<ChatMessageIndexEntity> indexes = messageIndexRepository.findByTenantIdAndSessionIdOrderByCreatedAtAsc(
            normalizedTenantId,
            session.getSessionId()
        );
        indexes.forEach(index -> detailStore.delete(index.getRocksKey()));
        messageIndexRepository.deleteByTenantIdAndSessionId(normalizedTenantId, session.getSessionId());
        summaryRepository.deleteBySessionId(session.getSessionId());
        sessionRepository.delete(session);
    }

    private void ensureConversationCanBeDeleted(ChatSessionEntity session) {
        String status = session == null || session.getStatus() == null
            ? ""
            : session.getStatus().trim().toLowerCase(Locale.ROOT);
        if (IN_PROGRESS_STATUSES.contains(status)) {
            throw new ConversationInProgressException(session.getSessionId());
        }
    }

    @Transactional
    public void deleteMessage(String tenantId, String conversationId, String messageId) {
        String normalizedTenantId = normalizeTenantId(tenantId);
        ChatSessionEntity session = sessionRepository
            .findBySessionIdAndTenantId(conversationId, normalizedTenantId)
            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        ChatMessageIndexEntity index = messageIndexRepository
            .findByMessageIdAndTenantIdAndSessionId(messageId, normalizedTenantId, session.getSessionId())
            .orElseThrow(() -> new IllegalArgumentException("Message not found in conversation: " + messageId));
        detailStore.delete(index.getRocksKey());
        messageIndexRepository.delete(index);
        // A persisted summary may contain content from the removed answer. Invalidate it so
        // future context assembly cannot resurrect deleted user-visible content.
        summaryRepository.deleteBySessionId(session.getSessionId());
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
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
        replaceMessages(DEFAULT_TENANT_ID, conversationId, userId, messages);
    }

    @Transactional
    public void replaceMessages(String tenantId, String conversationId, String userId, List<Conversation.Message> messages) {
        String normalizedTenantId = normalizeTenantId(tenantId);
        String normalizedConversationId = ensureConversationId(normalizedTenantId, conversationId, userId);
        ChatSessionEntity session = sessionRepository.findById(normalizedConversationId)
            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + normalizedConversationId));
        ensureTenant(session, normalizedTenantId);

        List<ChatMessageIndexEntity> existing = messageIndexRepository.findByTenantIdAndSessionIdOrderByCreatedAtAsc(normalizedTenantId, normalizedConversationId);
        existing.forEach(index -> detailStore.delete(index.getRocksKey()));
        messageIndexRepository.deleteByTenantIdAndSessionId(normalizedTenantId, normalizedConversationId);
        summaryRepository.deleteBySessionId(normalizedConversationId);

        Instant lastCreatedAt = session.getUpdatedAt() == null ? Instant.now() : session.getUpdatedAt();
        List<Conversation.Message> snapshot = collapseDuplicateAssistantResults(messages);
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
     * Merges a bounded client snapshot without deleting messages that were not
     * included because of the HTTP persistence budget. Incoming messages replace
     * matching IDs, while explicit truncation placeholders never downgrade an
     * already persisted complete message.
     */
    @Transactional
    public void mergeMessages(String tenantId,
                              String conversationId,
                              String userId,
                              List<Conversation.Message> messages) {
        List<Conversation.Message> existing = getConversation(tenantId, conversationId)
            .map(Conversation::getMessages)
            .orElse(List.of());
        replaceMessages(tenantId, conversationId, userId, mergeMessageSnapshots(existing, messages));
    }

    static List<Conversation.Message> mergeMessageSnapshots(List<Conversation.Message> existing,
                                                             List<Conversation.Message> incoming) {
        List<Conversation.Message> merged = new ArrayList<>(existing == null ? List.of() : existing);
        List<Conversation.Message> incomingSnapshot = incoming == null ? List.of() : incoming;
        int overlap = semanticOverlap(merged, incomingSnapshot);
        int overlapStart = merged.size() - overlap;
        for (int index = 0; index < overlap; index++) {
            int mergedIndex = overlapStart + index;
            Conversation.Message current = merged.get(mergedIndex);
            Conversation.Message candidate = incomingSnapshot.get(index);
            merged.set(mergedIndex, preferredEquivalentMessage(current, candidate));
        }
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (int index = 0; index < merged.size(); index++) {
            Conversation.Message message = merged.get(index);
            if (message != null && message.getId() != null && !message.getId().isBlank()) {
                positions.put(message.getId(), index);
            }
        }
        for (Conversation.Message message : incomingSnapshot.subList(overlap, incomingSnapshot.size())) {
            if (message == null) {
                continue;
            }
            Integer position = message.getId() == null ? null : positions.get(message.getId());
            if (position == null) {
                merged.add(message);
                if (message.getId() != null && !message.getId().isBlank()) {
                    positions.put(message.getId(), merged.size() - 1);
                }
                continue;
            }
            Conversation.Message previous = merged.get(position);
            preserveCompleteMessageFields(previous, message);
            merged.set(position, message);
        }
        return collapseDuplicateAssistantResults(merged);
    }

    private static int semanticOverlap(List<Conversation.Message> existing,
                                       List<Conversation.Message> incoming) {
        int maximum = Math.min(existing.size(), incoming.size());
        for (int size = maximum; size > 0; size--) {
            int existingStart = existing.size() - size;
            boolean matches = true;
            boolean userAnchored = false;
            boolean looselyMatchedAssistant = false;
            for (int index = 0; index < size; index++) {
                Conversation.Message existingMessage = existing.get(existingStart + index);
                Conversation.Message incomingMessage = incoming.get(index);
                if (!sameConversationMessage(existingMessage, incomingMessage)) {
                    matches = false;
                    break;
                }
                if ("user".equalsIgnoreCase(existingMessage.getRole())) {
                    userAnchored = true;
                } else if ("assistant".equalsIgnoreCase(existingMessage.getRole())
                    && !sameAssistantIdentity(existingMessage, incomingMessage)) {
                    looselyMatchedAssistant = true;
                }
            }
            if (matches && (!looselyMatchedAssistant || userAnchored)) {
                return size;
            }
        }
        return 0;
    }

    private static boolean sameConversationMessage(Conversation.Message first,
                                                   Conversation.Message second) {
        if (first == null || second == null || first.getRole() == null || second.getRole() == null
            || !first.getRole().equalsIgnoreCase(second.getRole())) {
            return false;
        }
        if ("assistant".equalsIgnoreCase(first.getRole())) {
            return isDuplicateAssistantResult(first, second);
        }
        return !normalizedText(first.getContent()).isBlank()
            && normalizedText(first.getContent()).equals(normalizedText(second.getContent()));
    }

    private static Conversation.Message preferredEquivalentMessage(Conversation.Message current,
                                                                    Conversation.Message candidate) {
        if (current == null) return candidate;
        if (candidate == null) return current;
        if ("assistant".equalsIgnoreCase(current.getRole()) && preferAssistantResult(candidate, current)) {
            preserveCompleteMessageFields(current, candidate);
            return candidate;
        }
        preserveCompleteMessageFields(candidate, current);
        return current;
    }

    private static void preserveCompleteMessageFields(Conversation.Message previous,
                                                      Conversation.Message incoming) {
        if (isTruncatedText(incoming.getContent()) && !isTruncatedText(previous.getContent())) {
            incoming.setContent(previous.getContent());
        }
        preserveCompleteMap(previous.getVisualizationSpec(), incoming.getVisualizationSpec(), incoming::setVisualizationSpec);
        preserveCompleteMap(previous.getUiResponse(), incoming.getUiResponse(), incoming::setUiResponse);
        preserveCompleteMap(previous.getAnalysisSelection(), incoming.getAnalysisSelection(), incoming::setAnalysisSelection);
        preserveCompleteList(previous.getSources(), incoming.getSources(), incoming::setSources);
        preserveCompleteList(previous.getTraces(), incoming.getTraces(), incoming::setTraces);
        preserveCompleteList(previous.getSteps(), incoming.getSteps(), incoming::setSteps);
        preserveCompleteList(previous.getEvidencePremises(), incoming.getEvidencePremises(), incoming::setEvidencePremises);
    }

    private static boolean isTruncatedText(String value) {
        return value != null && value.contains("...[message content truncated; originalBytes=");
    }

    private static boolean isTruncationMarker(Map<String, Object> value) {
        return value != null && Boolean.TRUE.equals(value.get("persistenceTruncated"));
    }

    private static void preserveCompleteMap(Map<String, Object> previous,
                                            Map<String, Object> incoming,
                                            java.util.function.Consumer<Map<String, Object>> setter) {
        if (isTruncationMarker(incoming) && !isTruncationMarker(previous)) {
            setter.accept(previous);
        }
    }

    private static void preserveCompleteList(List<Map<String, Object>> previous,
                                             List<Map<String, Object>> incoming,
                                             java.util.function.Consumer<List<Map<String, Object>>> setter) {
        boolean truncated = incoming != null && incoming.stream().anyMatch(ConversationService::isTruncationMarker);
        boolean previousTruncated = previous != null && previous.stream().anyMatch(ConversationService::isTruncationMarker);
        if (truncated && !previousTruncated) {
            setter.accept(previous);
        }
    }

    static List<Conversation.Message> collapseDuplicateAssistantResults(List<Conversation.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Conversation.Message> collapsed = new ArrayList<>();
        for (Conversation.Message message : messages) {
            Conversation.Message previous = collapsed.isEmpty() ? null : collapsed.get(collapsed.size() - 1);
            if (!isDuplicateAssistantResult(previous, message)) {
                collapsed.add(message);
            } else if (preferAssistantResult(message, previous)) {
                collapsed.set(collapsed.size() - 1, message);
            }
            collapseRepeatedTrailingTurn(collapsed);
        }
        return List.copyOf(collapsed);
    }

    private static void collapseRepeatedTrailingTurn(List<Conversation.Message> messages) {
        int size = messages.size();
        if (size < 4) {
            return;
        }
        Conversation.Message firstUser = messages.get(size - 4);
        Conversation.Message firstAssistant = messages.get(size - 3);
        Conversation.Message secondUser = messages.get(size - 2);
        Conversation.Message secondAssistant = messages.get(size - 1);
        if (!"user".equalsIgnoreCase(firstUser == null ? null : firstUser.getRole())
            || !"assistant".equalsIgnoreCase(firstAssistant == null ? null : firstAssistant.getRole())
            || !sameConversationMessage(firstUser, secondUser)
            || !isDuplicateAssistantResult(firstAssistant, secondAssistant)) {
            return;
        }
        Conversation.Message preferred = preferAssistantResult(secondAssistant, firstAssistant)
            ? secondAssistant : firstAssistant;
        messages.set(size - 3, preferred);
        messages.remove(size - 1);
        messages.remove(size - 2);
    }

    private static boolean isDuplicateAssistantResult(Conversation.Message first,
                                                      Conversation.Message second) {
        if (first == null || second == null
            || !"assistant".equalsIgnoreCase(first.getRole())
            || !"assistant".equalsIgnoreCase(second.getRole())) {
            return false;
        }
        String firstAnswer = normalizedAnswer(first);
        String secondAnswer = normalizedAnswer(second);
        if (firstAnswer.isBlank() || secondAnswer.isBlank()) {
            return false;
        }
        if (firstAnswer.equals(secondAnswer)
            || sameTaskId(first, second)
            || sameRawContent(first, second)) {
            return true;
        }

        // Agent execution and conversation memory persist the same turn independently.
        // The runtime copy carries a task id and the richer presentation metadata,
        // while the memory copy has no task id and can differ slightly after response
        // normalization. Consecutive copies still represent one assistant turn.
        return hasTaskId(first) != hasTaskId(second);
    }

    private static boolean hasTaskId(Conversation.Message message) {
        return message != null && message.getTaskId() != null && !message.getTaskId().isBlank();
    }

    private static boolean sameTaskId(Conversation.Message first, Conversation.Message second) {
        return hasTaskId(first)
            && hasTaskId(second)
            && first.getTaskId().trim().equals(second.getTaskId().trim());
    }

    private static boolean sameAssistantIdentity(Conversation.Message first,
                                                 Conversation.Message second) {
        return normalizedAnswer(first).equals(normalizedAnswer(second))
            || sameTaskId(first, second)
            || sameRawContent(first, second);
    }

    private static boolean sameRawContent(Conversation.Message first, Conversation.Message second) {
        String firstContent = normalizedText(first == null ? null : first.getContent());
        String secondContent = normalizedText(second == null ? null : second.getContent());
        return !firstContent.isBlank() && firstContent.equals(secondContent);
    }

    private static boolean preferAssistantResult(Conversation.Message candidate,
                                                 Conversation.Message current) {
        if (hasTaskId(candidate) != hasTaskId(current)) {
            return hasTaskId(candidate);
        }
        return presentationScore(candidate) > presentationScore(current);
    }

    private static String normalizedAnswer(Conversation.Message message) {
        if (message == null) {
            return "";
        }
        Object uiAnswer = message.getUiResponse() == null ? null : message.getUiResponse().get("answer");
        String content = uiAnswer == null || String.valueOf(uiAnswer).isBlank()
            ? message.getContent()
            : String.valueOf(uiAnswer);
        return normalizedText(content);
    }

    private static String normalizedText(String content) {
        return content == null ? "" : content.replaceAll("\\s+", " ").trim();
    }

    private static int presentationScore(Conversation.Message message) {
        if (message == null) {
            return 0;
        }
        int score = size(message.getSteps()) * 4 + size(message.getTraces()) * 3;
        score += message.getVisualizationSpec() == null || message.getVisualizationSpec().isEmpty() ? 0 : 6;
        score += message.getUiResponse() == null || message.getUiResponse().isEmpty() ? 0 : 2;
        score += message.getTaskId() == null || message.getTaskId().isBlank() ? 0 : 2;
        return score;
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
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
        return appendMessage(conversationId, role, content, sources, traces, Map.of());
    }

    @Transactional
    public Conversation.Message appendMessage(String conversationId,
                                              String role,
                                              String content,
                                              List<Map<String, Object>> sources,
                                              List<Map<String, Object>> traces,
                                              Map<String, Object> memoryContext) {
        return appendMessage(conversationId, role, content, sources, traces, memoryContext, null);
    }

    @Transactional
    public Conversation.Message appendMessage(String conversationId,
                                              String role,
                                              String content,
                                              List<Map<String, Object>> sources,
                                              List<Map<String, Object>> traces,
                                              Map<String, Object> memoryContext,
                                              String taskId) {
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
            .memoryContext(copyMap(memoryContext))
            .taskId(blankToNull(taskId))
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

    @Transactional(readOnly = true)
    public List<Conversation.Message> recentMessages(String tenantId, String conversationId, int limit) {
        if (limit <= 0 || getConversation(tenantId, conversationId).isEmpty()) {
            return List.of();
        }
        String normalizedTenantId = normalizeTenantId(tenantId);
        return messageIndexRepository.findByTenantIdAndSessionIdOrderByCreatedAtDesc(
                normalizedTenantId,
                conversationId,
                PageRequest.of(0, limit)
            ).stream()
            .sorted(Comparator.comparing(ChatMessageIndexEntity::getCreatedAt))
            .map(index -> detailStore.get(index.getRocksKey()).orElse(null))
            .filter(detail -> detail != null)
            .map(this::toMessage)
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ConversationSummary> latestSummary(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        return summaryRepository.findTopBySessionIdOrderByCreatedAtDesc(conversationId.trim())
            .map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public Optional<ConversationSummary> latestSummary(String tenantId, String conversationId) {
        if (conversationId == null || conversationId.isBlank() || getConversation(tenantId, conversationId).isEmpty()) {
            return Optional.empty();
        }
        return latestSummary(conversationId);
    }

    @Transactional(readOnly = true)
    public List<Conversation.Message> summaryCandidates(String conversationId, int keepRecentMessages) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<ChatMessageIndexEntity> indexes = messageIndexRepository.findBySessionIdOrderByCreatedAtAsc(conversationId);
        int endExclusive = Math.max(0, indexes.size() - Math.max(0, keepRecentMessages));
        if (endExclusive == 0) {
            return List.of();
        }
        int startInclusive = latestSummary(conversationId)
            .map(summary -> indexAfterMessage(indexes, summary.messageEndId()))
            .orElse(0);
        if (startInclusive >= endExclusive) {
            return List.of();
        }
        return indexes.subList(startInclusive, endExclusive).stream()
            .map(index -> detailStore.get(index.getRocksKey()).orElse(null))
            .filter(detail -> detail != null)
            .map(this::toMessage)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<Conversation.Message> summaryCandidates(String tenantId, String conversationId, int keepRecentMessages) {
        String normalizedTenantId = normalizeTenantId(tenantId);
        if (conversationId == null || conversationId.isBlank()
            || sessionRepository.findBySessionIdAndTenantId(conversationId.trim(), normalizedTenantId).isEmpty()) {
            return List.of();
        }
        List<ChatMessageIndexEntity> indexes = messageIndexRepository.findByTenantIdAndSessionIdOrderByCreatedAtAsc(
            normalizedTenantId,
            conversationId.trim()
        );
        int endExclusive = Math.max(0, indexes.size() - Math.max(0, keepRecentMessages));
        if (endExclusive == 0) {
            return List.of();
        }
        int startInclusive = latestSummary(normalizedTenantId, conversationId)
            .map(summary -> indexAfterMessage(indexes, summary.messageEndId()))
            .orElse(0);
        if (startInclusive >= endExclusive) {
            return List.of();
        }
        return indexes.subList(startInclusive, endExclusive).stream()
            .map(index -> detailStore.get(index.getRocksKey()).orElse(null))
            .filter(detail -> detail != null)
            .map(this::toMessage)
            .toList();
    }

    @Transactional
    public Optional<ConversationSummary> saveSummary(String conversationId,
                                                     String summary,
                                                     String messageStartId,
                                                     String messageEndId) {
        if (conversationId == null || conversationId.isBlank() || summary == null || summary.isBlank()
            || messageStartId == null || messageStartId.isBlank()
            || messageEndId == null || messageEndId.isBlank()) {
            return Optional.empty();
        }
        ConversationSummaryEntity entity = new ConversationSummaryEntity();
        entity.setSessionId(conversationId.trim());
        entity.setSummary(summary.trim());
        entity.setMessageStartId(messageStartId.trim());
        entity.setMessageEndId(messageEndId.trim());
        return Optional.of(toSummary(summaryRepository.save(entity)));
    }

    @Transactional
    public Optional<ConversationSummary> saveSummary(String tenantId,
                                                     String conversationId,
                                                     String summary,
                                                     String messageStartId,
                                                     String messageEndId) {
        if (conversationId == null || sessionRepository.findBySessionIdAndTenantId(conversationId.trim(), normalizeTenantId(tenantId)).isEmpty()) {
            return Optional.empty();
        }
        return saveSummary(conversationId, summary, messageStartId, messageEndId);
    }

    /**
     * Lists the message details.
     *
     * @param conversationId the conversation id value
     * @return the message details list
     */
    private List<Conversation.Message> listMessageDetails(String conversationId) {
        List<ChatMessageIndexEntity> indexes =
            messageIndexRepository.findBySessionIdOrderByCreatedAtAsc(conversationId);
        Map<String, ChatMessageDetail> details = detailStore.getAll(
            indexes.stream().map(ChatMessageIndexEntity::getRocksKey).toList());
        return indexes.stream()
            .map(index -> details.get(index.getRocksKey()))
            .filter(detail -> detail != null)
            .map(this::toMessage)
            .toList();
    }

    private List<Conversation.Message> listMessageDetails(String tenantId, String conversationId) {
        List<ChatMessageIndexEntity> indexes =
            messageIndexRepository.findByTenantIdAndSessionIdOrderByCreatedAtAsc(tenantId, conversationId);
        Map<String, ChatMessageDetail> details = detailStore.getAll(
            indexes.stream().map(ChatMessageIndexEntity::getRocksKey).toList());
        return indexes.stream()
            .map(index -> details.get(index.getRocksKey()))
            .filter(detail -> detail != null)
            .map(this::toMessage)
            .toList();
    }

    private int indexAfterMessage(List<ChatMessageIndexEntity> indexes, String messageId) {
        if (indexes == null || indexes.isEmpty() || messageId == null || messageId.isBlank()) {
            return 0;
        }
        for (int index = 0; index < indexes.size(); index++) {
            if (messageId.equals(indexes.get(index).getMessageId())) {
                return index + 1;
            }
        }
        return 0;
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
            .tenantId(session.getTenantId())
            .userId(session.getUserId())
            .title(session.getTitle())
            .status(session.getStatus())
            .skillId(session.getSkillId())
            .modelName(session.getModelName())
            .mode(session.getMode())
            .agentName(session.getAgentName())
            .createdAt(toLocalDateTime(session.getCreatedAt()))
            .updatedAt(toLocalDateTime(session.getUpdatedAt()))
            // Agent runtime snapshots and conversation memory can persist the same assistant
            // turn independently. Always collapse those copies on the read boundary as well:
            // a failed/late client snapshot must not make historical conversations look doubled.
            .messages(collapseDuplicateAssistantResults(messages))
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
            .content(UserFacingContentSanitizer.removeInternalEvidenceMarkers(detail.getContent()))
            .timestamp(toLocalDateTime(detail.getCreatedAt()))
            .toolsUsed(detail.getToolsUsed())
            .sourceKnowledgeBase(detail.getSourceKnowledgeBase())
            .sources(copyMaps(detail.getSources()))
            .traces(copyMaps(detail.getTraces()))
            .steps(copyMaps(detail.getSteps()))
            .visualizationSpec(copyMap(detail.getVisualizationSpec()))
            .uiResponse(UserFacingContentSanitizer.sanitizeUiResponse(copyMap(detail.getUiResponse())))
            .evidencePremises(copyMaps(detail.getEvidencePremises()))
            .memoryContext(copyMap(detail.getMemoryContext()))
            .agentName(detail.getAgentName())
            .modelName(detail.getModelName())
            .analysisNodeId(detail.getAnalysisNodeId())
            .analysisParentNodeId(detail.getAnalysisParentNodeId())
            .analysisSourceMessageId(detail.getAnalysisSourceMessageId())
            .analysisSelection(copyMap(detail.getAnalysisSelection()))
            .streaming(detail.getStreaming())
            .status(detail.getStatus())
            .taskId(detail.getTaskId())
            .build();
    }

    private ConversationSummary toSummary(ConversationSummaryEntity entity) {
        return new ConversationSummary(
            entity.getId(),
            entity.getSessionId(),
            entity.getSummary(),
            entity.getMessageStartId(),
            entity.getMessageEndId(),
            toLocalDateTime(entity.getCreatedAt())
        );
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
            .steps(copyMaps(message.getSteps()))
            .visualizationSpec(copyMap(message.getVisualizationSpec()))
            .uiResponse(copyMap(message.getUiResponse()))
            .evidencePremises(copyMaps(message.getEvidencePremises()))
            .memoryContext(copyMap(message.getMemoryContext()))
            .agentName(message.getAgentName())
            .modelName(message.getModelName())
            .analysisNodeId(message.getAnalysisNodeId())
            .analysisParentNodeId(message.getAnalysisParentNodeId())
            .analysisSourceMessageId(message.getAnalysisSourceMessageId())
            .analysisSelection(copyMap(message.getAnalysisSelection()))
            .streaming(message.getStreaming())
            .status(message.getStatus())
            .taskId(message.getTaskId())
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

    private Map<String, Object> copyMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(value);
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

    private String normalizeTenantId(String value) {
        return normalize(value, DEFAULT_TENANT_ID);
    }

    private void ensureTenant(ChatSessionEntity session, String tenantId) {
        if (session == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        String expected = normalizeTenantId(tenantId);
        String actual = normalizeTenantId(session.getTenantId());
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Conversation belongs to a different tenant");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
