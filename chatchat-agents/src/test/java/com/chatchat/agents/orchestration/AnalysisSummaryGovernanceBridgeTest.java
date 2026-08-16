package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.GovernanceIsolationScope;
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
    private final GovernanceIsolationScope isolationScope = GovernanceIsolationScope.runtime(
        "tenant-a", "user-a", "run-a", "request-a", "conversation-a");

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
            && prompt.contains("recordTo\":75")
            && prompt.contains("Identify position concentration risk")
            && prompt.contains("objective-relevant findings"))))
            .thenReturn("第 2 分块总结");
        Map<String, Object> context = bridge.govern("positions", Map.of(),
            List.of(Map.of("VALUE", 1)));
        AnalysisSummaryGovernanceBridge.ChunkPosition position =
            bridge.position("positions", 2, 3, 51, 75, 120);

        AnalysisSummaryResult summary = bridge.summarize(
            model, isolationScope, position, context, List.of(Map.of("VALUE", 1)),
            "Identify position concentration risk");
        Map<String, Object> ledger = bridge.ledger(List.of(summary), 120, 25, false);

        assertThat(summary.schemaVersion()).isEqualTo("analysis_summary_result.v1");
        assertThat(summary.content()).isEqualTo("第 2 分块总结");
        assertThat(summary.outcome()).isEqualTo("MODEL_SUMMARY");
        assertThat(summary.toMap().toString())
            .contains("scope=DATASET_CHUNK", "content=第 2 分块总结")
            .contains("chunkIndex=2", "recordFrom=51", "recordTo=75", "totalRecords=120")
            .contains("positions.records[51..75]");
        assertThat(ledger.toString())
            .contains("analysis_summary_bridge.v1", "summary_governance.v1", "第 2 分块总结");
        verify(model).chat(argThat((String prompt) -> prompt.contains("Missing semantic sections remain unknown")
            && prompt.contains("Lead with findings, not row counts or metadata")));
    }

    @Test
    void preservesExtensibleAssetSemanticsWithoutDomainSpecificBridgeCode() {
        Map<String, Object> governed = bridge.govern(
            "asset-snapshot",
            Map.of(
                "source", Map.of("displayName", "资产中心快照"),
                "semantics", Map.of(
                    "dimensions", List.of("account", "security"),
                    "measures", List.of("marketValue", "profitLoss"),
                    "timeSemantics", Map.of("asOfField", "businessDate"),
                    "units", Map.of("marketValue", "CNY")),
                "quality", Map.of("freshness", "T+1"),
                "analysisPolicy", Map.of("mode", "ANALYZE_RETURNED_RECORDS"),
                "extensions", Map.of("assetCenter", Map.of("valuationBasis", "close_price"))),
            List.of(Map.of("marketValue", 100, "profitLoss", -2)));

        assertThat(governed.get("semantics").toString())
            .contains("businessDate", "marketValue=CNY", "profitLoss");
        assertThat(governed.get("quality").toString()).contains("freshness=T+1");
        assertThat(governed.get("analysisPolicy").toString()).contains("ANALYZE_RETURNED_RECORDS");
        assertThat(governed.get("extensions").toString()).contains("valuationBasis=close_price");
        Map<?, ?> completeness = (Map<?, ?>) governed.get("contextCompleteness");
        assertThat(String.valueOf(completeness.get("missingSemanticSections")))
            .doesNotContain("semantics", "quality", "analysisPolicy", "extensions");
    }

    @Test
    void appliesExplicitAnalysisPolicyBeforeCallingTheModel() {
        assertThat(bridge.requiresModelSummary(Map.of(
            "source", Map.of("displayName", "资产目录"),
            "analysisPolicy", Map.of("mode", "REFERENCE_ONLY")), true)).isFalse();
        assertThat(bridge.requiresModelSummary(Map.of(
            "source", Map.of("displayName", "持仓结果"),
            "analysisPolicy", Map.of("mode", "ANALYZE_RETURNED_RECORDS")), false)).isTrue();
        assertThat(bridge.requiresModelSummary(Map.of(
            "source", Map.of("displayName", "受限结果"),
            "analysisPolicy", Map.of("enabled", false)), false)).isFalse();
    }

    @Test
    void finalModelContentIsStoredInTheCanonicalGovernedResultObject() {
        Map<String, Object> context = bridge.govern("assets", Map.of(),
            List.of(Map.of("TOTAL_ASSET", 847174.25)));
        AnalysisSummaryResult chunk = bridge.preserve(
            isolationScope, bridge.position("assets", 1, 1, 1, 1, 1), context,
            List.of(Map.of("TOTAL_ASSET", 847174.25)));

        AnalysisSummaryResult result = bridge.finalResult(
            isolationScope,
            "initial", "资产分析总结正文", "MODEL_FINAL_SUMMARY",
            Map.of("returnedRecordCount", 1, "processedRecordCount", 1, "coverageComplete", true),
            List.of(chunk));

        assertThat(result.schemaVersion()).isEqualTo("analysis_summary_result.v1");
        assertThat(result.scope()).isEqualTo("FINAL_SYNTHESIS");
        assertThat(result.content()).isEqualTo("资产分析总结正文");
        assertThat(result.inputSummaryResultIds()).containsExactly("tenant-a:run-a:assets#chunk-1");
        assertThat(result.toMap().toString())
            .contains("MODEL_FINAL_SUMMARY", "summary_governance.v1")
            .contains("GOVERNED_ANALYSIS_SUMMARY", "RETURNED_STRUCTURED_EVIDENCE")
            .contains("coverageComplete=true");
    }

    @Test
    void rejectsCrossTenantSummaryLineage() {
        Map<String, Object> context = bridge.govern("assets", Map.of(),
            List.of(Map.of("TOTAL_ASSET", 1)));
        AnalysisSummaryResult foreignChunk = bridge.preserve(
            GovernanceIsolationScope.runtime(
                "tenant-b", "user-b", "run-b", "request-b", "conversation-b"),
            bridge.position("assets", 1, 1, 1, 1, 1), context,
            List.of(Map.of("TOTAL_ASSET", 1)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bridge.finalResult(
                isolationScope, "initial", "summary", "MODEL_FINAL_SUMMARY", Map.of(), List.of(foreignChunk)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Cross-tenant or cross-run");
    }
}
