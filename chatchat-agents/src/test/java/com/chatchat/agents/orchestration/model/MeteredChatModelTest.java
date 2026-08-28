package com.chatchat.agents.orchestration.model;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
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

    private ChatModel modelReturning(String value) {
        return new ChatModel() {
            @Override
            public String chat(String message) {
                return value;
            }
        };
    }
}
