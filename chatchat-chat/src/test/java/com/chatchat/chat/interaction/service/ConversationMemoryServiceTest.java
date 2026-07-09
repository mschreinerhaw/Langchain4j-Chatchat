package com.chatchat.chat.interaction.service;

import com.chatchat.chat.conversation.Conversation;
import com.chatchat.chat.conversation.ConversationService;
import com.chatchat.chat.conversation.ConversationSummary;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.model.InteractionSource;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void refreshSummarySupportsUnlimitedSummaryMaxChars() {
        ConversationService conversationService = mock(ConversationService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        ConversationContextProperties properties = new ConversationContextProperties();
        properties.setSummaryTriggerMessages(2);
        properties.setSummaryKeepRecentMessages(1);
        properties.setSummaryMaxChars(-1);
        ConversationMemoryService memoryService = new ConversationMemoryService(
            conversationService,
            new ObjectMapper(),
            chatModelProvider,
            properties
        );

        String longPriorSummary = "prior-" + "x".repeat(900);
        when(chatModelProvider.getIfAvailable()).thenReturn(null);
        when(conversationService.latestSummary("conv-1")).thenReturn(Optional.of(new ConversationSummary(
            "summary-1",
            "conv-1",
            longPriorSummary,
            "m-old",
            "m-old",
            java.time.LocalDateTime.now()
        )));
        when(conversationService.summaryCandidates("conv-1", 1)).thenReturn(messages(2));

        memoryService.maybeRefreshSummary("conv-1");

        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationService).saveSummary(eq("conv-1"), summaryCaptor.capture(), eq("m-0"), eq("m-1"));
        assertThat(summaryCaptor.getValue()).contains(longPriorSummary);
    }

    @Test
    @SuppressWarnings("unchecked")
    void responseMemoryContextKeepsEvidenceSignalsButDropsDebugPayload() {
        ConversationService conversationService = mock(ConversationService.class);
        ConversationMemoryService memoryService = new ConversationMemoryService(
            conversationService,
            new ObjectMapper(),
            null,
            new ConversationContextProperties()
        );

        InteractionResponse response = InteractionResponse.builder()
            .answer("final answer")
            .sources(List.of(InteractionSource.builder()
                .rank(1)
                .source("doc://file-1#chunk=2")
                .snippet("important fact")
                .build()))
            .toolTraces(List.of(InteractionToolTrace.builder()
                .toolName("document_search")
                .success(true)
                .build()))
            .metadata(Map.of(
                "handler", "AgentChatModeHandler",
                "skillId", "ops",
                "agent", Map.of(
                    "groundingStatus", "grounded",
                    "answerDecision", "quality_selected_answer",
                    "answerRewriteSource", "quality_aggregator",
                    "answerQualitySelectedId", "candidate",
                    "answerDecisionTrace", Map.of("debug", true),
                    "observations", List.of("large raw observation"),
                    "evidenceAnswer", Map.of(
                        "confidence", "high",
                        "answer", "Evidence-backed summary",
                        "citations", List.of(Map.of("refId", "doc://file-1#chunk=2"))
                    )
                )
            ))
            .build();

        Map<String, Object> memoryContext = memoryService.responseMemoryContext(response);

        assertThat(memoryContext)
            .containsEntry("contractVersion", "conversation_memory_context_v1")
            .containsEntry("groundingStatus", "grounded")
            .containsEntry("answerDecision", "quality_selected_answer")
            .containsEntry("answerRewriteSource", "quality_aggregator")
            .doesNotContainKeys("answerDecisionTrace", "observations");
        assertThat((List<Map<String, Object>>) memoryContext.get("citations"))
            .extracting(item -> item.get("refId"))
            .contains("doc://file-1#chunk=2");
        assertThat(memoryService.memoryContextText(memoryContext))
            .contains("grounding=grounded")
            .contains("citations=doc://file-1#chunk=2")
            .contains("tools=document_search");
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
