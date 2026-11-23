package com.chatchat.agents.orchestration.retrieval;

import com.chatchat.common.tool.ToolMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClineSearchInputTest {
    @Test
    void distinguishesUnboundNumericInputsFromAssetIdentityAndCapabilityTerms() {
        var numeric = Map.<String, Object>of("queryTerms", List.of("123456"));
        assertThat(ClineSearchInput.hasOnlyUnboundNumericTerms(numeric, Map.of())).isTrue();
        assertThat(ClineSearchInput.hasOnlyUnboundNumericTerms(numeric, Map.of("assetName", "123456"))).isFalse();
        assertThat(ClineSearchInput.hasOnlyUnboundNumericTerms(
            Map.of("queryTerms", List.of("cluster 123456 metrics")), Map.of())).isFalse();
    }

    @Test
    void appliesThePublishedBudgetBeforeCallingTheTool() {
        var tool = ToolMetadata.builder().metadata(Map.of("mcpToolMeta", Map.of(
            "modelInputBridgeContract", Map.of("maximumQueryTerms", 1)))).build();
        var input = Map.<String, Object>of("query", "original narrative",
            "targetObject", Map.of("name", "confirmed identity"));
        assertThat(ClineSearchInput.maximumTerms(tool)).isEqualTo(1);
        assertThatThrownBy(() -> ClineSearchInput.apply("search", tool, input,
            Map.of("queryTerms", List.of("first", "second"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(ClineSearchInput.apply("search", tool, input, Map.of("queryTerms", List.of("first"))))
            .isEqualTo(Map.of("queryTerms", List.of("first"), "targetObject", input.get("targetObject")));
    }
}
