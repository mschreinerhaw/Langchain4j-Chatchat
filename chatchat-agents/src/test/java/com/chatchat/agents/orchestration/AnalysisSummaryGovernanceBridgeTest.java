package com.chatchat.agents.orchestration;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisSummaryGovernanceBridgeTest {

    private final AnalysisSummaryGovernanceBridge bridge = new AnalysisSummaryGovernanceBridge();

    @Test
    void supplementsStructureWithoutInventingMissingBusinessSemantics() {
        Map<String, Object> governed = bridge.govern(
            "portfolio-result",
            Map.of("source", Map.of("displayName", "Portfolio positions")),
            List.of(Map.of("SECURITY_CODE", "600000", "MARKET_VALUE", 12000)));

        assertThat(governed)
            .containsEntry("schemaVersion", "data_analysis_context.v1");
        assertThat(governed.get("governance").toString())
            .contains("summary_governance.v1", "analysis_summary_bridge.v1")
            .contains("PRESERVE_RETURNED_FIELD_KEYS");
        assertThat(governed.get("source").toString())
            .contains("Portfolio positions", "runtimeReference=portfolio-result");
        assertThat(governed.get("schema").toString())
            .contains("SECURITY_CODE", "MARKET_VALUE")
            .doesNotContain("证券代码", "资产市值");
        assertThat(governed.get("contextCompleteness").toString())
            .contains("missingSemanticSections", "capability", "business", "relationships")
            .contains("semanticInferenceAllowed=false");
    }

    @Test
    void modelChunkSummaryCarriesAndRecordsItsExactPosition() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(argThat((String prompt) -> prompt.contains("analysis_summary_bridge.v1")
            && prompt.contains("datasetReference\":\"positions")
            && prompt.contains("recordFrom\":51")
            && prompt.contains("recordTo\":75"))))
            .thenReturn("第 2 分块总结");
        Map<String, Object> context = bridge.govern("positions", Map.of(),
            List.of(Map.of("VALUE", 1)));
        AnalysisSummaryGovernanceBridge.ChunkPosition position =
            bridge.position("positions", 2, 3, 51, 75, 120);

        AnalysisSummaryGovernanceBridge.ChunkSummary summary = bridge.summarize(
            model, position, context, List.of(Map.of("VALUE", 1)));
        Map<String, Object> ledger = bridge.ledger(List.of(summary), 120, 25, false);

        assertThat(summary.summary()).isEqualTo("第 2 分块总结");
        assertThat(summary.outcome()).isEqualTo("MODEL_SUMMARY");
        assertThat(summary.toMap().toString())
            .contains("chunkIndex=2", "recordFrom=51", "recordTo=75", "totalRecords=120")
            .contains("positions.records[51..75]");
        assertThat(ledger.toString())
            .contains("analysis_summary_bridge.v1", "summary_governance.v1", "第 2 分块总结");
        verify(model).chat(argThat((String prompt) -> prompt.contains("Missing semantic sections remain unknown")));
    }
}
