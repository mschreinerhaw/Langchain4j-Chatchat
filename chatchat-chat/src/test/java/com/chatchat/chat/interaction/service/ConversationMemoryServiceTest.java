package com.chatchat.chat.interaction.service;

import com.chatchat.chat.conversation.Conversation;
import com.chatchat.chat.conversation.ConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationMemoryServiceTest {

    @Test
    void refreshSummaryKeepsRecentMessagesAndSavesCondensedContext() {
        ConversationService conversationService = mock(ConversationService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        ConversationContextProperties properties = new ConversationContextProperties();
        properties.setSummaryTriggerMessages(3);
        properties.setSummaryKeepRecentMessages(2);
        properties.setSummaryMaxChars(600);
        ConversationMemoryService memoryService = new ConversationMemoryService(
            conversationService,
            new ObjectMapper(),
            chatModelProvider,
            properties
        );

        when(chatModelProvider.getIfAvailable()).thenReturn(null);
        when(conversationService.latestSummary("conv-1")).thenReturn(Optional.empty());
        when(conversationService.summaryCandidates("conv-1", 2)).thenReturn(messages(3));

        memoryService.maybeRefreshSummary("conv-1");

        verify(conversationService).summaryCandidates("conv-1", 2);
        verify(conversationService).saveSummary(eq("conv-1"), anyString(), eq("m-0"), eq("m-2"));
    }

    @Test
    void refreshSummarySkipsWhenCandidateWindowIsBelowThreshold() {
        ConversationService conversationService = mock(ConversationService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        ConversationContextProperties properties = new ConversationContextProperties();
        properties.setSummaryTriggerMessages(4);
        properties.setSummaryKeepRecentMessages(2);
        ConversationMemoryService memoryService = new ConversationMemoryService(
            conversationService,
            new ObjectMapper(),
            chatModelProvider,
            properties
        );

        when(conversationService.summaryCandidates("conv-1", 2)).thenReturn(messages(3));

        memoryService.maybeRefreshSummary("conv-1");

        verify(conversationService, never()).saveSummary(anyString(), anyString(), anyString(), anyString());
    }

    private List<Conversation.Message> messages(int count) {
        List<Conversation.Message> messages = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            messages.add(Conversation.Message.builder()
                .id("m-" + index)
                .role(index % 2 == 0 ? "user" : "assistant")
                .content("message " + index)
                .build());
        }
        return messages;
    }
}
