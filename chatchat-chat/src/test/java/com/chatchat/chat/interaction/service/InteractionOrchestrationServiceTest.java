package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InteractionOrchestrationServiceTest {

    @Test
    void recognizesUserQuestionAlreadyPersistedByConversationSnapshot() {
        List<ConversationMemoryService.MessageSnapshot> history = List.of(
            new ConversationMemoryService.MessageSnapshot("assistant", "上一轮回答", 1L, Map.of()),
            new ConversationMemoryService.MessageSnapshot("user", "分析客户年化收益率", 2L, Map.of())
        );

        assertThat(InteractionOrchestrationService.latestMessageIsSameUserQuestion(
            history,
            "  分析客户年化收益率  "
        )).isTrue();
    }

    @Test
    void allowsNewQuestionWhenLatestPersistedMessageIsAnAssistantAnswer() {
        List<ConversationMemoryService.MessageSnapshot> history = List.of(
            new ConversationMemoryService.MessageSnapshot("user", "分析客户年化收益率", 1L, Map.of()),
            new ConversationMemoryService.MessageSnapshot("assistant", "分析结果", 2L, Map.of())
        );

        assertThat(InteractionOrchestrationService.latestMessageIsSameUserQuestion(
            history,
            "分析客户年化收益率"
        )).isFalse();
    }

    @Test
    void allowsDifferentQuestion() {
        List<ConversationMemoryService.MessageSnapshot> history = List.of(
            new ConversationMemoryService.MessageSnapshot("user", "分析客户年化收益率", 1L, Map.of())
        );

        assertThat(InteractionOrchestrationService.latestMessageIsSameUserQuestion(
            history,
            "分析客户累计收益率"
        )).isFalse();
    }

    @Test
    void sanitizesReconciliationDetailsAtUnifiedResponseBoundary() {
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        when(memoryService.ensureConversationId("default", null, "user-1")).thenReturn("conversation-1");
        when(memoryService.summary("default", "conversation-1")).thenReturn(Optional.empty());
        when(memoryService.conversationEvidenceProjection("default", "conversation-1", 100, 32))
            .thenReturn("");
        when(memoryService.recent("default", "conversation-1", 8)).thenReturn(List.of());

        InteractionModeHandler handler = mock(InteractionModeHandler.class);
        when(handler.mode()).thenReturn(InteractionMode.LLM_CHAT);
        when(handler.handle(any(), any())).thenReturn(InteractionResponse.builder()
            .answer("结论已生成 [SQL-1]。\n\nSTEP-1 =`iteration:1:step:1:tool:mcp_server_tool`")
            .build());

        InteractionOrchestrationService service = new InteractionOrchestrationService(
            List.of(handler), memoryService);
        InteractionResponse response = service.chat(InteractionRequest.builder()
            .userId("user-1")
            .mode("llm_chat")
            .query("生成结论")
            .build());

        assertThat(response.getAnswer()).isEqualTo("结论已生成。");
    }
}
