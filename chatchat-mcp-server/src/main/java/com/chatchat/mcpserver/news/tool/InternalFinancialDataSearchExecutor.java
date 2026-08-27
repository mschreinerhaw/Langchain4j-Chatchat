package com.chatchat.mcpserver.news.tool;

import com.chatchat.mcpserver.news.financial.FinancialEnrichmentService;

import com.chatchat.common.tool.ToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal financial-data stage used by the web-search bridge.
 *
 * <p>The executor deliberately derives its query from the original web_search input. This keeps
 * financial asset discovery, table mapping, and news retrieval on one query context instead of
 * allowing a second agent-planned tool call to rewrite or narrow the keywords.</p>
 */
final class InternalFinancialDataSearchExecutor {
    private static final Logger log = LoggerFactory.getLogger(InternalFinancialDataSearchExecutor.class);
    static final String TOOL_NAME = "financial_data_search";
    static final String PURPOSE = "web_search_bridge_financial_analysis";

    private final FinancialEnrichmentService financialData;

    InternalFinancialDataSearchExecutor(FinancialEnrichmentService financialData) {
        this.financialData = financialData;
    }

    SearchResult search(ToolInput source, int candidateLimit) {
        ToolInput input = source == null ? ToolInput.builder().build() : source;
        String query = input.getParameterAsString("query", "").trim();
        ToolInput bridgeInput = bridgeInput(input);
        log.info("internalFinancialDataSearch started parentTool=web_search query=\"{}\" candidateLimit={}",
            query, candidateLimit);
        FinancialEnrichmentService.EnrichmentResult enrichment =
            financialData.enrich(query, bridgeInput, candidateLimit);
        log.info("internalFinancialDataSearch completed parentTool=web_search inputQueryForwarded=true "
                + "assetQuery=\"{}\" assetCount={} datasetCount={} skippedReason={}",
            enrichment.assetQuery(), enrichment.assets().size(), enrichment.financialData().size(),
            enrichment.skippedReason());
        return new SearchResult(query, enrichment);
    }

    Map<String, Object> queryDataset(String dataset, ToolInput input) {
        return financialData.queryDataset(dataset, bridgeInput(input));
    }

    private ToolInput bridgeInput(ToolInput source) {
        Map<String, Object> parameters = new LinkedHashMap<>(source.getParameters() == null
            ? Map.of() : source.getParameters());
        Map<String, Object> context = new LinkedHashMap<>(source.getContext() == null
            ? Map.of() : source.getContext());
        context.putIfAbsent("internalPurpose", PURPOSE);
        if (!FinancialEnrichmentService.FINAL_SUMMARY_PURPOSE.equals(
            String.valueOf(context.get("internalPurpose")))) {
            parameters.put("financial_data_required", true);
        }
        context.put("bridgeParentTool", "web_search");
        context.put("userFacingTool", false);
        return ToolInput.builder()
            .rawInput(source.getRawInput())
            .parameters(parameters)
            .requestId(source.getRequestId())
            .userId(source.getUserId())
            .conversationId(source.getConversationId())
            .context(context)
            .build();
    }

    record SearchResult(String query, FinancialEnrichmentService.EnrichmentResult enrichment) {
    }
}
