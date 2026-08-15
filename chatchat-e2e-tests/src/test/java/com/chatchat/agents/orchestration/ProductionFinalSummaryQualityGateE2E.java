package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentRuntimeProperties;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Release regression: complete first-round evidence must not trigger a duplicate final-summary web call. */
class ProductionFinalSummaryQualityGateE2E {

    @Test
    void completeFinancialWebEvidenceWinsOverStructuredFinancialPolicyWithoutSecondToolCall() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolRuntimeService runtime = mock(ToolRuntimeService.class);
        ChatModel model = mock(ChatModel.class);
        String webTool = "mcp_chatchat_mcp_server_web_search";
        when(registry.getAllToolNames()).thenReturn(Set.of(webTool));
        when(registry.getToolMetadata(webTool)).thenReturn(ToolMetadata.builder()
            .id(webTool)
            .parameters(List.of(ToolParameter.builder()
                .name("financial_data_required").type("boolean").build()))
            .build());
        when(model.chat(any(String.class))).thenReturn(
            "{\"needed\":false,\"financialDataRequired\":false,\"keywords\":[],"
                + "\"reason\":\"Existing evidence fully supports the market review\"}");
        InteractionToolTrace successfulFirstRound = InteractionToolTrace.builder()
            .toolName(webTool)
            .success(true)
            .output("financialDataSatisfied=true; retrievalSource=governed_financial_store; coverage=10/10")
            .build();
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setFinalSummaryWebSearchEnabled(true);
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of(
            "agentRunId", "quality-gate-release-regression",
            "forceStructuredFinancialData", true));

        FinalSummaryWebSearchEnhancer.Enhancement result = new FinalSummaryWebSearchEnhancer(
            registry, runtime, new ObjectMapper(), properties).enhance(
                model,
                "生成2026年8月15日A股收盘复盘报告",
                "",
                "已有主要指数、成交量、重大公告和风险提示。",
                List.of("结构化金融行情与新闻证据覆盖校验：10/10（完整）"),
                List.of(successfulFirstRound),
                metadata);

        verify(runtime, never()).execute(any());
        assertThat(result.attempted()).isFalse();
        assertThat(result.traces()).containsExactly(successfulFirstRound);
        assertThat(metadata)
            .containsEntry("finalSummaryWebSearchDecision", false)
            .containsEntry("finalSummaryFinancialDataDecisionSource", "QUALITY_GATE")
            .containsEntry("finalSummaryWebSearchSkippedReason", "existing_evidence_sufficient");
    }
}
