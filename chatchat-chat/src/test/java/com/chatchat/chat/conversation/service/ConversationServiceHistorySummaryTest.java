package com.chatchat.chat.conversation.service;

import com.chatchat.chat.conversation.store.ChatMessageDetailStore;

import com.chatchat.chat.conversation.persistence.ChatMessageIndexRepository;

import com.chatchat.chat.conversation.persistence.ConversationSummaryRepository;

import com.chatchat.chat.conversation.persistence.ChatSessionRepository;

import com.chatchat.chat.conversation.persistence.ChatSessionEntity;

import com.chatchat.chat.conversation.service.ConversationService;

import com.chatchat.chat.conversation.model.Conversation;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceHistorySummaryTest {

    @Test
    void agentApiConversationIsMarkedOutsidePlatformHistoryChannel() {
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        when(sessionRepository.findById("api-session")).thenReturn(Optional.empty());
        when(sessionRepository.save(any(ChatSessionEntity.class))).thenAnswer(invocation -> {
            ChatSessionEntity session = invocation.getArgument(0);
            session.onCreate();
            return session;
        });
        ConversationService service = new ConversationService(
            sessionRepository, mock(ChatMessageIndexRepository.class),
            mock(ConversationSummaryRepository.class), mock(ChatMessageDetailStore.class));

        String id = service.ensureConversationId(
            "tenant-1", "api-session", "user-1", "AGENT_API");

        ArgumentCaptor<ChatSessionEntity> session = ArgumentCaptor.forClass(ChatSessionEntity.class);
        verify(sessionRepository).save(session.capture());
        assertEquals("api-session", id);
        assertEquals("AGENT_API", session.getValue().getSourceChannel());
    }

    @Test
    void summaryListReadsOnlySessionHeaders() {
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatMessageIndexRepository messageIndexRepository = mock(ChatMessageIndexRepository.class);
        ConversationSummaryRepository summaryRepository = mock(ConversationSummaryRepository.class);
        ChatMessageDetailStore detailStore = mock(ChatMessageDetailStore.class);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setSessionId("conversation-1");
        session.setTenantId("tenant-1");
        session.setUserId("user-1");
        session.setTitle("历史标题");
        session.setStatus("completed");
        session.onCreate();
        when(sessionRepository.findPlatformByTenantIdAndUserIdOrderByUpdatedAtDesc(
            eq("tenant-1"), eq("user-1"), any(Pageable.class)
        )).thenReturn(List.of(session));

        ConversationService service = new ConversationService(
            sessionRepository,
            messageIndexRepository,
            summaryRepository,
            detailStore
        );

        List<Conversation> summaries = service.listUserConversationSummaries("tenant-1", "user-1", 30);

        assertEquals(1, summaries.size());
        assertEquals("历史标题", summaries.get(0).getTitle());
        assertTrue(summaries.get(0).getMessages().isEmpty());
        verify(messageIndexRepository, never()).findByTenantIdAndSessionIdOrderByCreatedAtAsc(anyString(), anyString());
        verify(detailStore, never()).get(anyString());
        verify(detailStore, never()).getAll(anyList());
    }

    @Test
    void summaryListUsesRequestedHistoryPageWithoutReadingMessages() {
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatMessageIndexRepository messageIndexRepository = mock(ChatMessageIndexRepository.class);
        ConversationSummaryRepository summaryRepository = mock(ConversationSummaryRepository.class);
        ChatMessageDetailStore detailStore = mock(ChatMessageDetailStore.class);
        when(sessionRepository.findPlatformByTenantIdAndUserIdOrderByUpdatedAtDesc(
            eq("tenant-1"), eq("user-1"), any(Pageable.class)
        )).thenReturn(List.of());
        ConversationService service = new ConversationService(
            sessionRepository, messageIndexRepository, summaryRepository, detailStore);

        service.listUserConversationSummaries("tenant-1", "user-1", 1, 30);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionRepository).findPlatformByTenantIdAndUserIdOrderByUpdatedAtDesc(
            eq("tenant-1"), eq("user-1"), pageable.capture());
        assertEquals(1, pageable.getValue().getPageNumber());
        assertEquals(30, pageable.getValue().getPageSize());
        verify(messageIndexRepository, never()).findByTenantIdAndSessionIdOrderByCreatedAtAsc(anyString(), anyString());
    }

    @Test
    void partialSnapshotPreservesOmittedHistoryAndCompletePersistedContent() {
        Conversation.Message old = message("old", "user", "earlier question", List.of());
        Conversation.Message complete = message(
            "answer", "assistant", "complete answer", List.of(Map.of("output", "complete trace")));
        Conversation.Message truncated = message(
            "answer",
            "assistant",
            "preview\n...[message content truncated; originalBytes=999999]",
            List.of(Map.of("persistenceTruncated", true, "preview", "trace preview")));
        Conversation.Message newest = message("new", "user", "follow-up", List.of());

        List<Conversation.Message> merged = ConversationService.mergeMessageSnapshots(
            List.of(old, complete), List.of(truncated, newest));

        assertEquals(List.of("old", "answer", "new"), merged.stream().map(Conversation.Message::getId).toList());
        assertEquals("complete answer", merged.get(1).getContent());
        assertEquals("complete trace", merged.get(1).getTraces().get(0).get("output"));
    }

    private Conversation.Message message(String id,
                                         String role,
                                         String content,
                                         List<Map<String, Object>> traces) {
        return Conversation.Message.builder()
            .id(id)
            .role(role)
            .content(content)
            .timestamp(LocalDateTime.now())
            .traces(traces)
            .sources(List.of())
            .steps(List.of())
            .evidencePremises(List.of())
            .build();
    }
}
