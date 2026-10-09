package com.chatchat.chat.conversation.service;

import com.chatchat.chat.conversation.store.ChatMessageDetailStore;
import com.chatchat.chat.conversation.persistence.*;
import com.chatchat.chat.conversation.model.ChatMessageDetail;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationVisualizationPreferenceTest {
    @Test
    @SuppressWarnings("unchecked")
    void persistsCompleteEditorSettingsAndPreservesThemWhenOnlyChartTypeChanges() {
        var sessions = mock(ChatSessionRepository.class);
        var indexes = mock(ChatMessageIndexRepository.class);
        var store = mock(ChatMessageDetailStore.class);
        var session = new ChatSessionEntity();
        session.setSessionId("conversation");
        var index = new ChatMessageIndexEntity();
        index.setRocksKey("detail");
        var detail = ChatMessageDetail.builder().messageId("answer").role("assistant").content("Report").build();
        when(sessions.findBySessionIdAndTenantId("conversation", "tenant")).thenReturn(Optional.of(session));
        when(indexes.findByMessageIdAndTenantIdAndSessionId("answer", "tenant", "conversation")).thenReturn(Optional.of(index));
        when(store.get("detail")).thenReturn(Optional.of(detail));
        var service = new ConversationService(sessions, indexes, mock(ConversationSummaryRepository.class), store);
        service.updateVisualizationPreference("tenant", "conversation", "answer", "table:sample", "graph", "line",
            Map.of("xKey", "period", "yKey", "value", "groupKey", "segment", "selectedColumns", List.of("period", "value")));
        var restored = new ConversationService(sessions, indexes, mock(ConversationSummaryRepository.class), store)
            .updateVisualizationPreference("tenant", "conversation", "answer", "table:sample", "graph", "bar");
        var ui = (Map<String, Object>) restored.getVisualizationSpec().get("ui");
        var preferences = (Map<String, Object>) ui.get("userPreferences");
        assertThat((Map<String, Object>) preferences.get("table:sample"))
            .containsEntry("chartType", "bar").containsEntry("xKey", "period")
            .containsEntry("groupKey", "segment").containsEntry("selectedColumns", List.of("period", "value"));
        verify(store, times(2)).put(detail);
        assertThatThrownBy(() -> service.updateVisualizationPreference("other", "conversation", "answer", "table:sample", "graph", "bar"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
