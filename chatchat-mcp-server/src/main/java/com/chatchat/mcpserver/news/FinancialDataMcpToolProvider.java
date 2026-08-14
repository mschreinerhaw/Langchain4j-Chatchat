package com.chatchat.mcpserver.news;

import com.chatchat.common.concurrent.CancellationSupport;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.runtime.mcp.registry.McpCapabilityCodes;
import com.chatchat.runtime.mcp.registry.McpToolDefinition;
import com.chatchat.runtime.mcp.registry.McpToolExecutor;
import com.chatchat.runtime.mcp.registry.McpToolProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Legacy compatibility wrapper for direct financial-data calls.
 *
 * <p>It is intentionally not a Spring component: production search exposes only web_search and
 * runs this capability as an internal bridge stage, preventing independent query rewriting.</p>
 */
public class FinancialDataMcpToolProvider implements McpToolProvider {
    public static final String TOOL_NAME = InternalFinancialDataSearchExecutor.TOOL_NAME;

    private final InternalFinancialDataSearchExecutor financialData;
    private final McpToolDefinition definition;

    public FinancialDataMcpToolProvider(FinancialEnrichmentService financialData) {
        this.financialData = new InternalFinancialDataSearchExecutor(financialData);
        this.definition = new McpToolDefinition(
            TOOL_NAME,
            "Local Structured Financial Data Search",
            "Search and query governed financial observations collected by this platform. This tool never searches "
                + "the public web. Use it together with web_search when an answer needs both locally collected market "
                + "data and current news. With query only, datasets are selected dynamically from the governed asset "
                + "catalog and matching observations are returned. With dataset, that exact discovered dataset is "
                + "queried. Never invent or hardcode dataset codes.",
            McpCapabilityCodes.NEWS,
            "chatchat-mcp-server",
            List.of(
                text("query", "Original financial question used for dynamic dataset discovery and entity resolution", false),
                text("dataset", "Optional exact dataset code returned by this tool's assets result", false),
                object("filters", "Optional exact-match filters using fields advertised by the selected dataset"),
                text("startDate", "Optional observation start date in YYYY-MM-DD", false),
                text("endDate", "Optional observation end date in YYYY-MM-DD", false),
                text("historyMode", "Storage tier: auto, daily, or weekly", false),
                text("discovery_id", "Optional discovery identifier returned by web_search for evidence-chain auditing", false),
                number("financial_dataset_limit", "Maximum dynamically matched datasets to query", 3, 1, 3),
                number("financial_row_limit", "Maximum rows per dynamically matched dataset", 20, 1, 50),
                number("limit", "Maximum rows when an exact dataset is supplied", 50, 1, 200)
            ),
            true,
            true,
            Duration.ofSeconds(20)
        );
    }

    @Override
    public String capabilityCode() {
        return McpCapabilityCodes.NEWS;
    }

    @Override
    public Collection<McpToolDefinition> definitions() {
        return List.of(definition);
    }

    @Override
    public Optional<McpToolExecutor> findExecutor(String toolName) {
        return TOOL_NAME.equals(toolName) ? Optional.of(this::execute) : Optional.empty();
    }

    private ToolOutput execute(ToolInput source) {
        CancellationSupport.throwIfCancelled("financial_data_search");
        ToolInput input = source == null ? ToolInput.builder().build() : source;
        String dataset = input.getParameterAsString("dataset", "").trim();
        String query = input.getParameterAsString("query", "").trim();
        if (dataset.isBlank() && query.isBlank()) {
            return ToolOutput.failure("query parameter is required when dataset is absent");
        }
        try {
            Map<String, Object> response = dataset.isBlank()
                ? discoverAndQuery(query, forceFinancialRetrieval(input))
                : queryDataset(dataset, input);
            ToolOutput output = ToolOutput.success(response, "Local structured financial data query completed");
            output.getMetadata().put("resultType", "local_structured_financial_data");
            output.getMetadata().put("retrievalSource", "governed_financial_store");
            output.getMetadata().put("networkSearchUsed", false);
            output.getMetadata().put("dataset", dataset);
            return output;
        } catch (Exception ex) {
            CancellationSupport.rethrowIfCancelled(ex, "financial_data_search");
            return ToolOutput.failure(ex);
        }
    }

    private Map<String, Object> discoverAndQuery(String query, ToolInput input) {
        int requested = bounded(input.getParameterAsNumber("financial_dataset_limit"), 3, 1, 3);
        FinancialEnrichmentService.EnrichmentResult result = financialData.search(input, requested).enrichment();
        List<Map<String, Object>> observations = result.financialData().stream()
            .map(item -> compactResult(item, input, "financial_row_limit", 20, 50)).toList();
        Map<String, Object> response = base(query);
        response.put("mode", "DYNAMIC_DISCOVERY_AND_QUERY");
        response.put("assetQuery", result.assetQuery());
        response.put("assets", result.assets());
        response.put("assetCount", result.assets().size());
        response.put("financialData", observations);
        response.put("datasetCount", observations.size());
        response.put("observationCount", observations.stream()
            .mapToInt(item -> ((Number) item.getOrDefault("count", 0)).intValue()).sum());
        response.put("coverageComplete", !observations.isEmpty() && result.warnings().isEmpty());
        if (!result.warnings().isEmpty()) response.put("warnings", result.warnings());
        if (result.skippedReason() != null) response.put("skippedReason", result.skippedReason());
        return response;
    }

    private Map<String, Object> queryDataset(String dataset, ToolInput input) {
        Map<String, Object> response = base(input.getParameterAsString("query", ""));
        Map<String, Object> queried = compactResult(financialData.queryDataset(dataset, input), input,
            "limit", 50, 200);
        response.put("mode", "EXACT_DATASET_QUERY");
        response.put("dataset", dataset);
        String discoveryId = input.getParameterAsString("discovery_id", "").trim();
        if (!discoveryId.isBlank()) response.put("discovery_id", discoveryId);
        response.put("financialData", List.of(queried));
        response.put("datasetCount", 1);
        response.put("observationCount", queried.getOrDefault("count", 0));
        response.put("coverageComplete", ((Number) queried.getOrDefault("count", 0)).intValue() > 0);
        return response;
    }

    private ToolInput forceFinancialRetrieval(ToolInput source) {
        Map<String, Object> parameters = new LinkedHashMap<>(source.getParameters() == null
            ? Map.of() : source.getParameters());
        parameters.put("financial_data_required", true);
        return ToolInput.builder()
            .rawInput(source.getRawInput())
            .parameters(parameters)
            .requestId(source.getRequestId())
            .userId(source.getUserId())
            .conversationId(source.getConversationId())
            .context(source.getContext() == null ? Map.of() : new LinkedHashMap<>(source.getContext()))
            .build();
    }

    private Map<String, Object> base(String query) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion", "financial_data_search_result.v1");
        response.put("query", query == null ? "" : query);
        response.put("resultType", "local_structured_financial_data");
        response.put("retrievalSource", "governed_financial_store");
        response.put("provider", "chatchat-mcp-market");
        response.put("networkSearchUsed", false);
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compactResult(Map<String, Object> source, ToolInput input,
                                              String limitParameter, int fallback, int max) {
        Map<String, Object> result = new LinkedHashMap<>(source == null ? Map.of() : source);
        int limit = bounded(input.getParameterAsNumber(limitParameter), fallback, 1, max);
        List<Map<String, Object>> rows = source != null && source.get("rows") instanceof List<?> values
            ? values.stream().filter(Map.class::isInstance)
                .map(value -> compactRow((Map<String, Object>) value)).limit(limit).toList()
            : List.of();
        result.put("rows", rows);
        result.put("count", rows.size());
        result.put("emptyResult", rows.isEmpty());
        result.put("resultView", "compact_model_context");
        return result;
    }

    private Map<String, Object> compactRow(Map<String, Object> source) {
        Map<String, Object> row = new LinkedHashMap<>();
        List<String> omitted = new ArrayList<>();
        source.forEach((key, value) -> {
            String normalized = key == null ? "" : key.toLowerCase();
            if (value == null || value instanceof String text && text.isBlank()) return;
            if ("payload_json".equals(normalized) || normalized.endsWith("_history")
                || value instanceof String text && text.length() > 4000) {
                omitted.add(key);
            } else {
                row.put(key, value);
            }
        });
        if (!omitted.isEmpty()) row.put("_omitted_fields", List.copyOf(omitted));
        return Map.copyOf(row);
    }

    private int bounded(Number value, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, value == null ? fallback : value.intValue()));
    }

    private ToolParameter text(String name, String description, boolean required) {
        return ToolParameter.builder().name(name).type("string").description(description).required(required).build();
    }

    private ToolParameter object(String name, String description) {
        return ToolParameter.builder().name(name).type("object").description(description).required(false).build();
    }

    private ToolParameter number(String name, String description, int value, int min, int max) {
        return ToolParameter.builder().name(name).type("number").description(description).required(false)
            .defaultValue(value).minimum(min).maximum(max).build();
    }
}
