package com.chatchat.agents.orchestration;

import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpAnalysisContextAdapterTest {

    private final McpAnalysisContextAdapter adapter =
        new McpAnalysisContextAdapter(new ObjectMapper());

    @Test
    void extractsWhitelistedMcpMetadataAndMergesReturnedContext() {
        ToolMetadata metadata = ToolMetadata.builder()
            .id("mcp_asset_center_position_query")
            .title("资产中心持仓查询")
            .description("查询客户账户持仓快照")
            .author("MCP:asset-center")
            .categories(List.of("mcp", "asset_analysis"))
            .category("asset_analysis")
            .tags(List.of("portfolio", "position"))
            .outputType("json")
            .metadata(Map.of(
                "serviceId", "asset-center",
                "remoteToolName", "position_query",
                "tenantId", "must-not-enter-semantic-context",
                "mcpToolMeta", Map.of(
                    "capabilitySpec", Map.of("supportedScenarios", List.of("position_analysis")),
                    "businessGroup", Map.of("name", "客户资产"),
                    "outputSchema", "{\"type\":\"object\"}",
                    "dependencySpec", Map.of("accountId", "assetAccount.accountId"),
                    "semantics", Map.of("granularity", "account-security-day"),
                    "quality", Map.of("freshnessField", "businessDate"),
                    "analysisPolicy", Map.of("mode", "ANALYZE_RETURNED_RECORDS"),
                    "extensions", Map.of("assetCenter", Map.of("valuationBasis", "close_price"))
                )))
            .build();

        Map<String, Object> context = adapter.adapt("positions", metadata, Map.of(
            "analysisContext", Map.of(
                "semantics", Map.of("currency", "CNY"),
                "quality", Map.of("asOf", "2026-07-31"))));

        assertThat(context.get("source").toString())
            .contains("资产中心持仓查询", "asset-center", "position_query");
        assertThat(context.get("capability").toString()).contains("position_analysis");
        assertThat(context.get("business").toString()).contains("客户资产");
        assertThat(context.get("schema").toString()).contains("type=object");
        assertThat(context.get("relationships").toString()).contains("assetAccount.accountId");
        assertThat(context.get("semantics").toString())
            .contains("account-security-day", "currency=CNY");
        assertThat(context.get("quality").toString())
            .contains("freshnessField=businessDate", "asOf=2026-07-31");
        assertThat(context.get("analysisPolicy").toString()).contains("ANALYZE_RETURNED_RECORDS");
        assertThat(context.get("extensions").toString()).contains("valuationBasis=close_price");
        assertThat(context.toString()).doesNotContain("must-not-enter-semantic-context", "tenantId");
    }

    @Test
    void childDatasetInheritsRootContextAndOverridesOnlyItsOwnSections() {
        Map<String, Object> root = Map.of(
            "source", Map.of("displayName", "资产中心"),
            "capability", Map.of("scenario", "asset_analysis"),
            "semantics", Map.of("currency", "CNY", "timeBasis", "business_date"),
            "quality", Map.of("freshness", "T+1"));
        Map<String, Object> child = Map.of(
            "dataset", "positions",
            "analysisContext", Map.of(
                "source", Map.of("displayName", "持仓明细"),
                "semantics", Map.of("granularity", "account-security-day")),
            "rows", List.of(Map.of("marketValue", 100)));

        Map<String, Object> merged = adapter.adaptDataset(root, child);

        assertThat(merged.get("source").toString()).contains("displayName=持仓明细");
        assertThat(merged.get("capability").toString()).contains("asset_analysis");
        assertThat(merged.get("semantics").toString())
            .contains("currency=CNY", "timeBasis=business_date", "account-security-day");
        assertThat(merged.get("quality").toString()).contains("freshness=T+1");
        assertThat(merged.toString()).doesNotContain("marketValue");
    }

    @Test
    void ordinaryNonMcpToolMetadataDoesNotTurnProtocolRowsIntoBusinessAnalysis() {
        ToolMetadata metadata = ToolMetadata.builder()
            .id("document_parser")
            .title("Document parser")
            .categories(List.of("document"))
            .build();

        assertThat(adapter.adapt("documents", metadata, Map.of("rows", List.of(Map.of("id", 1)))))
            .isEmpty();
    }
}
