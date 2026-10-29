package com.chatchat.chat.conversation.service;

import com.chatchat.chat.conversation.persistence.*;
import com.chatchat.chat.conversation.store.ChatMessageDetailStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudioConversationChannelTest {
    final ChatSessionRepository sessions=mock(ChatSessionRepository.class);
    final ConversationService service=new ConversationService(sessions,mock(ChatMessageIndexRepository.class),
        mock(ConversationSummaryRepository.class),mock(ChatMessageDetailStore.class));
    ChatSessionEntity session(String user){
        var row=new ChatSessionEntity();row.setSessionId("session");row.setTenantId("tenant");row.setUserId(user);
        when(sessions.findById("session")).thenReturn(Optional.of(row));
        when(sessions.findBySessionIdAndTenantId("session","tenant")).thenReturn(Optional.of(row));return row;
    }
    @Test void classificationPersistsAndOrdinaryEnsureDoesNotChangeIt(){
        var row=session("owner");service.classifyStudioConversation("tenant","session","owner","SQL_STUDIO");
        service.ensureConversationId("tenant","session","owner","PLATFORM_UI");
        assertThat(row.getSourceChannel()).isEqualTo("SQL_STUDIO");verify(sessions).saveAndFlush(row);
        assertThatThrownBy(()->service.classifyStudioConversation("tenant","session","owner","PYTHON_STUDIO")).isInstanceOf(SecurityException.class);
    }
    @Test void rejectsAnotherOwner(){session("other");assertThatThrownBy(()->service.classifyStudioConversation("tenant","session","owner","SQL_STUDIO")).isInstanceOf(SecurityException.class);}
    @Test void rejectsAnotherTenant(){session("owner");assertThatThrownBy(()->service.classifyStudioConversation("other","session","owner","SQL_STUDIO")).isInstanceOf(IllegalArgumentException.class);}
    @Test void rejectsArbitraryChannel(){assertThatThrownBy(()->service.classifyStudioConversation("tenant","session","owner","CHAT")).isInstanceOf(IllegalArgumentException.class);}
}
