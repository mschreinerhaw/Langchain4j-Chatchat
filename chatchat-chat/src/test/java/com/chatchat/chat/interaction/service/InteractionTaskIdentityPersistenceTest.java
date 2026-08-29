package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InteractionTaskIdentityPersistenceTest {

    @Test
    void persistsAgentTaskIdentityWithTheMemoryAnswer() {
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        when(memoryService.ensureConversationId("tenant-1", "conversation-1", "user-1"))
            .thenReturn("conversation-1");
        when(memoryService.summary("tenant-1", "conversation-1")).thenReturn(Optional.empty());
        when(memoryService.conversationEvidenceProjection("tenant-1", "conversation-1", 100, 32))
            .thenReturn("");
        when(memoryService.recent("tenant-1", "conversation-1", 8)).thenReturn(List.of());
        when(memoryService.responseMemoryContext(any(), any(), any(), any())).thenReturn(Map.of());

        InteractionModeHandler handler = mock(InteractionModeHandler.class);
        when(handler.mode()).thenReturn(InteractionMode.LLM_CHAT);
        when(handler.handle(any(), any())).thenReturn(InteractionResponse.builder()
            .answer("one final answer")
            .build());

        InteractionOrchestrationService service = new InteractionOrchestrationService(
            List.of(handler), memoryService);
        service.chat(InteractionRequest.builder()
            .tenantId("tenant-1")
            .conversationId("conversation-1")
            .userId("user-1")
            .mode("llm_chat")
            .query("question")
            .toolInput(Map.of("__agentTaskId", "task-1"))
            .build());

        verify(memoryService).append(
            "conversation-1",
            "assistant",
            "one final answer",
            List.of(),
            List.of(),
            Map.of(),
            "task-1"
        );
    }
}
