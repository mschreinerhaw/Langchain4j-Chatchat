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

    @Test
    void recordsFailureWithoutLosingOriginalExceptionOrInventingOutputTokens() {
        Map<String, Object> usage = new LinkedHashMap<>();
        IllegalStateException failure = new IllegalStateException("transport failure");
        ChatModel delegate = new ChatModel() {
            @Override public String chat(String message) { throw failure; }
        };
        MeteredChatModel model = new MeteredChatModel(delegate, usage, 0, 0, 0, 0, 0.8);
        assertThatThrownBy(() -> model.chat("private prompt")).isSameAs(failure);
        assertThat(usage.get("invocations")).isEqualTo(1L);
        assertThat(usage.get("failedInvocations")).isEqualTo(1L);
        assertThat(usage.get("activeInvocations")).isEqualTo(0);
        Map<?, ?> call = (Map<?, ?>) ((List<?>) usage.get("calls")).get(0);
        assertThat(call.get("status")).isEqualTo("FAILED");
        assertThat(call.get("outputTokensEstimated")).isNull();
        assertThat(call.get("inputTokens")).isNull();
        assertThat(call.toString()).doesNotContain("private prompt", "transport failure");
    }

    @Test
    void recordsActualUsageSeparatelyAndMeasuresDeadlineQueue() {
        Map<String, Object> usage = new LinkedHashMap<>();
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("done"))
                    .tokenUsage(new dev.langchain4j.model.output.TokenUsage(123, 17)).build();
            }
        };
        MeteredChatModel model = new MeteredChatModel(
            new DeadlineAwareChatModel(delegate, () -> 10_000L), usage, 0, 0, 0, 0, 0.8);
        model.chat(ChatRequest.builder().messages(UserMessage.from("hello")).build());
        Map<?, ?> call = (Map<?, ?>) ((List<?>) usage.get("calls")).get(0);
        assertThat(call.get("inputTokens")).isEqualTo(123);
        assertThat(call.get("outputTokens")).isEqualTo(17);
        assertThat(((Number) call.get("queueTimeMs")).longValue()).isNotNegative();
        assertThat(((Number) call.get("executionTimeMs")).longValue()
            + ((Number) call.get("queueTimeMs")).longValue()).isEqualTo(call.get("elapsedMs"));
        assertThat(call.get("nodeName").toString()).contains("recordsActualUsageSeparately");
        assertThat(usage.get("criticalPathLlmCalls")).isNull();
    }

    @Test
    void concurrentCallsReserveInputAndPublishIndependentSnapshots() throws Exception {
        Map<String, Object> usage = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        String prompt = "some meaningful input to reserve against the budget";
        long tokens = new com.chatchat.agents.orchestration.analysis.context.ContextTokenEstimator()
            .estimate(prompt).tokens();
        ChatModel delegate = new ChatModel() {
            @Override public String chat(String message) {
                entered.countDown();
                try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return "";
            }
        };
        MeteredChatModel model = new MeteredChatModel(delegate, usage, tokens, 0, 0, 0, 0.8);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> model.chat(prompt));
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                List<?> snapshot = (List<?>) usage.get("calls");
                assertThatThrownBy(() -> model.chat(prompt)).isInstanceOf(AgentBudgetExceededException.class);
                release.countDown();
                first.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(((Map<?, ?>) snapshot.get(0)).get("status")).isEqualTo("RUNNING");
                assertThat(usage.get("invocations")).isEqualTo(1L);
            } finally { release.countDown(); }
        } finally { executor.shutdownNow(); }
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
