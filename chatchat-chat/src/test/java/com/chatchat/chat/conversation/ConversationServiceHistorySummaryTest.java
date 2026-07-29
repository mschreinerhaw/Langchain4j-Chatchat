package com.chatchat.chat.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

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
        when(sessionRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(
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
}
