package com.chatchat.agents.orchestration.model;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeteredChatModelTest {

    @Test
    void accountsTokensLatencyAndEstimatedCost() {
        Map<String, Object> usage = new LinkedHashMap<>();
        ChatModel model = new MeteredChatModel(modelReturning("response text"), usage,
            1_000, 10D, 0.01D, 0.02D, 0.8D);

        assertThat(model.chat("hello world")).isEqualTo("response text");
        assertThat(((Number) usage.get("invocations")).longValue()).isEqualTo(1L);
        assertThat(((Number) usage.get("totalTokensEstimated")).longValue()).isPositive();
        assertThat(((Number) usage.get("estimatedCost")).doubleValue()).isPositive();
    }

    @Test
    void rejectsInvocationBeforeKnownPromptWouldExceedBudget() {
        ChatModel model = new MeteredChatModel(modelReturning("unused"), new LinkedHashMap<>(),
            1, 0D, 0D, 0D, 0.8D);

        assertThatThrownBy(() -> model.chat("this prompt is larger than one token"))
            .isInstanceOf(AgentBudgetExceededException.class)
            .hasMessageContaining("TOKEN_BUDGET");
    }

    @Test
    void forwardsNativeChatRequestAndAccountsInvocation() {
        Map<String, Object> usage = new LinkedHashMap<>();
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                ToolExecutionRequest call = ToolExecutionRequest.builder()
                    .id("call-1").name("document_search").arguments("{}").build();
                return ChatResponse.builder().aiMessage(AiMessage.from(call)).build();
            }
        };
        MeteredChatModel model = new MeteredChatModel(
            delegate, usage, 1_000, 10D, 0.01D, 0.02D, 0.8D);

        ChatResponse response = model.chat(ChatRequest.builder()
            .messages(List.of(UserMessage.from("search")))
            .build());

        assertThat(response.aiMessage().toolExecutionRequests()).singleElement()
            .satisfies(call -> assertThat(call.name()).isEqualTo("document_search"));
        assertThat(((Number) usage.get("invocations")).longValue()).isEqualTo(1L);
    }

    private ChatModel modelReturning(String value) {
        return new ChatModel() {
            @Override
            public String chat(String message) {
                return value;
            }
        };
    }
}
