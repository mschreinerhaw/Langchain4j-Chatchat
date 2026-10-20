package com.chatchat.agents.orchestration.model;

import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelUsageMetricsTest {
    @Test void reportsOnlyProviderObservedCacheAndReasoningCounts() {
        var plain = new TokenUsage(100, 20);
        assertThat(ModelUsageMetrics.observed(plain))
            .containsEntry("inputTokens", 100L).containsEntry("outputTokens", 20L)
            .doesNotContainKeys("cacheReadTokens", "reasoningTokens");

        var detailed = OpenAiTokenUsage.builder().inputTokenCount(100).outputTokenCount(20)
            .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(40).build())
            .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(5).build())
            .build();
        assertThat(ModelUsageMetrics.observed(detailed))
            .containsEntry("inputTokens", 100L).containsEntry("outputTokens", 20L)
            .containsEntry("cacheReadTokens", 40L).containsEntry("reasoningTokens", 5L);
    }
}
