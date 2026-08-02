package com.chatchat.agents.orchestration;

import com.chatchat.common.interaction.InteractionToolTrace;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Production regression: one missing tool result must not erase usable analysis. */
class ProductionPartialEvidenceAnswerPreservationE2E {

    @Test
    void mixedEvidencePreservesAnalysisAndExposesCoverageBoundary() {
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            null,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        InteractionToolTrace usableNews = InteractionToolTrace.builder()
            .toolName("mcp_runtime_news_web_search")
            .success(true)
            .output("{\"success\":true,\"results\":[{\"title\":\"policy and company evidence\"}]}")
            .build();
        InteractionToolTrace unavailableMarketData = InteractionToolTrace.builder()
            .toolName("mcp_runtime_financial_web_search")
            .success(false)
            .errorMessage("structured financial provider timed out")
            .build();
        String candidate = "# 今日A股开盘分析\n\n基于已获得的政策与公司信息，可继续分析市场情绪、风险和关注方向。";

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            candidate,
            List.of(usableNews, unavailableMarketData),
            new LinkedHashMap<>(),
            List.of("policy evidence available", "structured market observations unavailable")
        );

        assertThat(result.answer())
            .contains("# 今日A股开盘分析")
            .contains("市场情绪、风险和关注方向")
            .contains("数据覆盖说明")
            .contains("工具结果仅部分覆盖本次需求")
            .doesNotStartWith("工具调用没有产生可解析、可信的结果");
        assertThat(result.metadata())
            .containsEntry("mcpResultEvidenceAvailability", "PARTIAL")
            .containsEntry("mcpAvailableResultCount", 1)
            .containsEntry("mcpFailedToolCount", 1)
            .containsEntry("mcpUnavailableResultCount", 1)
            .containsEntry("mcpResultAnalysisCapability", "PARTIAL")
            .containsEntry("evidenceLimitedAnalysisPreserved", true);
    }

    @Test
    void successfulHistoricalMapTraceCannotEraseEvidenceSufficientAnalysis() {
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            null,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        String marker = "MARKET_ANALYSIS_" + UUID.randomUUID();
        InteractionToolTrace usableSearch = InteractionToolTrace.builder()
            .toolName("runtime_search_" + UUID.randomUUID())
            .success(true)
            .output("{results=[{title=traceable market evidence, url=https://example.test/market}], count=1}")
            .build();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stopReason", "evidence_sufficient");

        AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
            "# Market analysis " + marker + "\n\nA bounded conclusion based only on the returned evidence.",
            List.of(usableSearch),
            metadata,
            List.of("traceable market evidence")
        );

        assertThat(result.answer())
            .contains(marker)
            .doesNotContain("工具调用没有产生可解析、可信的结果");
        assertThat(result.metadata())
            .containsEntry("mcpResultEvidenceAvailability", "AVAILABLE")
            .containsEntry("mcpResultAnswerAllowed", true)
            .containsEntry("mcpResultAnalysisCapability", "FULL");
    }
}
