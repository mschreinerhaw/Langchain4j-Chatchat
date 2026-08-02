package com.chatchat.mcpserver.news;

import com.chatchat.common.concurrent.CancellationSupport;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.runtime.mcp.registry.McpCapabilityCodes;
import com.chatchat.runtime.mcp.registry.McpToolDefinition;
import com.chatchat.runtime.mcp.registry.McpToolExecutor;
import com.chatchat.runtime.mcp.registry.McpToolProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The only public search tool; it composes independent news and market runtimes. */
@Component
public class RemoteNewsMcpToolProvider implements McpToolProvider {
    private final NewsSearchService newsSearch;
    private final Optional<FinancialEnrichmentService> financialEnrichment;
    private final Map<String, McpToolDefinition> definitions;

    public RemoteNewsMcpToolProvider(NewsSearchService newsSearch,
                                     Optional<FinancialEnrichmentService> financialEnrichment) {
        this.newsSearch = newsSearch;
        this.financialEnrichment = financialEnrichment == null ? Optional.empty() : financialEnrichment;
        McpToolDefinition webSearch = definition("web_search", "Unified Web Search",
            "Unified one-call retrieval for current hotspots, place names, knowledge beyond the local corpus, "
                + "news, and governed financial data. External search is an internal recall enhancer and is not "
                + "a separate user-facing tool. The tool searches the compatible "
                + "financial-data-asset index without implicitly reading financial rows. To read authoritative "
                + "observations, call financial_data_search with the business question or an explicit dataset code "
                + "returned by discovery. Dataset execution through web_search remains compatibility-only.",
            List.of(text("query", "News topic, business question, or financial data keywords", false),
                number("num_results", "Maximum number of unified search results to return", 10, 1, 50),
                bool("financial_data_required", "Explicit Runtime retrieval-intent marker. Set true only when the answer "
                    + "requires authoritative structured financial observations in addition to news. Runtime selects "
                    + "datasets dynamically from the governed asset catalog; never guess a dataset code.", false),
                number("financial_dataset_limit", "Maximum dynamically matched datasets when financial_data_required is true", 2, 1, 3),
                number("financial_row_limit", "Maximum rows per dynamically matched financial dataset", 20, 1, 50),
                text("dataset", "Optional dataset code returned by a financial_data_asset result", false),
                object("filters", "Exact-match filters on registered fields, such as securityCode or quoteCode"),
                text("startDate", "Optional observation start date in YYYY-MM-DD", false),
                text("endDate", "Optional observation end date in YYYY-MM-DD", false),
                text("historyMode", "Storage tier: auto (default), daily (7-day hot data), or weekly (snapshots)", false),
                text("discovery_id", "Discovery identifier returned by the first call; pass it back for retrieval-chain auditing", false),
                number("limit", "Maximum observation rows to return when dataset is provided", 50, 1, 200)), true, 30);
        this.definitions = Map.of(webSearch.name(), webSearch);
    }

    @Override public String capabilityCode() { return McpCapabilityCodes.NEWS; }
    @Override public Collection<McpToolDefinition> definitions() { return definitions.values(); }
    @Override public Optional<McpToolExecutor> findExecutor(String toolName) {
        return definitions.containsKey(toolName) ? Optional.of(this::execute) : Optional.empty();
    }

    private ToolOutput execute(ToolInput input) {
        CancellationSupport.throwIfCancelled("unified web_search");
        String dataset = input.getParameterAsString("dataset", "").trim();
        if (!dataset.isBlank()) {
            try {
                int rowLimit = bounded(input.getParameterAsNumber("limit"), 50, 1, 200);
                FinancialEnrichmentService enrichment = financialEnrichment.orElseThrow(() ->
                    new IllegalStateException("Financial query capability is unavailable"));
                Map<String, Object> data = new LinkedHashMap<>(enrichment.queryDataset(dataset, input));
                List<Map<String, Object>> compactRows = compactRows(rows(data), rowLimit);
                data.put("rows", compactRows);
                data.put("count", compactRows.size());
                data.put("resultView", "compact_model_context");
                data.put("provider", "chatchat-mcp-market");
                data.put("mode", "financial_dataset_query");
                data.put("result_type", "financial_dataset_query");
                data.put("retrieval_stage", "EXECUTION");
                data.put("sample_only", false);
                data.put("requires_second_query", false);
                data.put("empty_result", compactRows.isEmpty());
                String discoveryId = input.getParameterAsString("discovery_id", "").trim();
                if (!discoveryId.isBlank()) data.put("discovery_id", discoveryId);
                ToolOutput result = ToolOutput.success(data, "Financial dataset query completed");
                result.getMetadata().put("financialRetrievalStage", "EXECUTION");
                result.getMetadata().put("financialDataset", dataset);
                result.getMetadata().put("financialEmptyResult", compactRows.isEmpty());
                if (!discoveryId.isBlank()) result.getMetadata().put("financialDiscoveryId", discoveryId);
                return result;
            } catch (Exception ex) {
                CancellationSupport.rethrowIfCancelled(ex, "financial dataset query");
                return ToolOutput.failure(ex);
            }
        }

        String query = input.getParameterAsString("query", "").trim();
        if (query.isBlank()) return ToolOutput.failure("query parameter is required when dataset is absent");
        String discoveryId = UUID.randomUUID().toString();
        int limit = bounded(input.getParameterAsNumber("num_results"), 10, 1, 50);
        NewsSearchService.SearchResult newsResult = newsSearch.search(input);
        List<Map<String, Object>> news = newsResult.results();
        List<String> warnings = new ArrayList<>();
        if (newsResult.warning() != null) warnings.add(newsResult.warning());
        CancellationSupport.throwIfCancelled("unified web_search");
        FinancialEnrichmentService.EnrichmentResult enrichment = financialEnrichment
            .map(service -> service.enrich(query, input, limit))
            .orElseGet(() -> new FinancialEnrichmentService.EnrichmentResult(
                query, List.of(), List.of(), List.of(), "capability_unavailable"));
        warnings.addAll(enrichment.warnings());
        String financialAssetQuery = enrichment.assetQuery();
        List<Map<String, Object>> assets = enrichment.assets().stream()
            .map(source -> assetResult(source, discoveryId)).toList();
        List<Map<String, Object>> financialData = enrichment.financialData().stream()
            .map(item -> compactFinancialResult(item, input)).toList();
        CancellationSupport.throwIfCancelled("unified web_search");
        if (news.isEmpty() && assets.isEmpty() && warnings.size() == 2) {
            return ToolOutput.failure("Both news and market search are unavailable: " + String.join("; ", warnings));
        }

        List<Map<String, Object>> marketResults = new ArrayList<>(financialData);
        marketResults.addAll(assets);
        List<Map<String, Object>> results = interleave(news, marketResults, limit);
        List<String> urls = results.stream().map(item -> item.get("url")).filter(String.class::isInstance)
            .map(String.class::cast).filter(value -> !value.isBlank()).distinct().toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("financialAssetQuery", financialAssetQuery);
        data.put("discovery_id", discoveryId);
        data.put("result_type", "unified_search_results");
        data.put("retrieval_stage", "DISCOVERY");
        data.put("sample_only", false);
        data.put("requires_second_query", !assets.isEmpty());
        data.put("provider", "chatchat-unified-search");
        data.put("mode", "unified_news_and_financial_asset_discovery");
        data.put("count", results.size());
        data.put("newsCount", news.size());
        data.put("financialAssetCount", assets.size());
        data.put("financialAssets", assets);
        data.put("financialIndex", financialIndexGuide(assets, discoveryId));
        data.put("financialDatasetCount", financialData.size());
        data.put("financialObservationCount", financialData.stream()
            .mapToInt(item -> ((Number) item.getOrDefault("count", 0)).intValue()).sum());
        data.put("financialData", financialData);
        boolean financialDataRequired = input.getParameterAsBoolean("financial_data_required", false);
        data.put("financialDataRequired", financialDataRequired);
        data.put("financialDataSatisfied", !financialData.isEmpty()
            && financialData.stream().anyMatch(item -> ((Number) item.getOrDefault("count", 0)).intValue() > 0));
        if (enrichment.skippedReason() != null) data.put("financialEnrichmentSkippedReason", enrichment.skippedReason());
        data.put("results", results);
        data.put("reference_urls", urls);
        if (!warnings.isEmpty()) data.put("warnings", warnings);
        ToolOutput result = ToolOutput.success(data, "Unified news and financial asset discovery completed");
        result.getMetadata().put("financialRetrievalStage", "DISCOVERY");
        result.getMetadata().put("financialDiscoveryId", discoveryId);
        result.getMetadata().put("financialCandidateDatasetCount", assets.size());
        result.getMetadata().put("financialQueriedDatasetCount", financialData.size());
        result.getMetadata().put("financialDataRequired", financialDataRequired);
        result.getMetadata().put("financialSecondQueryRequired", !assets.isEmpty());
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> result) {
        if (result == null || !(result.get("rows") instanceof List<?> values)) return List.of();
        return values.stream().filter(Map.class::isInstance)
            .map(value -> (Map<String, Object>) new LinkedHashMap<>((Map<String, Object>) value)).toList();
    }

    private List<Map<String, Object>> compactRows(List<Map<String, Object>> rows, int limit) {
        LinkedHashMap<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> source : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            List<String> omitted = new ArrayList<>();
            source.forEach((key, value) -> {
                String normalized = key == null ? "" : key.toLowerCase();
                if (value == null || (value instanceof String text && text.isBlank())) {
                    return;
                } else if ("payload_json".equals(normalized) || normalized.endsWith("_history")) {
                    omitted.add(key);
                } else if (value instanceof String text && text.length() > 4000) {
                    omitted.add(key);
                } else {
                    row.put(key, value);
                }
            });
            if (!omitted.isEmpty()) row.put("_omitted_fields", List.copyOf(omitted));
            String key = String.valueOf(source.getOrDefault("record_key", row.hashCode())) + "|"
                + source.getOrDefault("observation_date", "");
            unique.putIfAbsent(key, row);
            if (unique.size() >= limit) break;
        }
        return List.copyOf(unique.values());
    }

    private Map<String, Object> compactFinancialResult(Map<String, Object> source, ToolInput input) {
        Map<String, Object> result = new LinkedHashMap<>(source == null ? Map.of() : source);
        int rowLimit = bounded(input.getParameterAsNumber("financial_row_limit"), 20, 1, 50);
        List<Map<String, Object>> compact = compactRows(rows(result), rowLimit);
        result.put("rows", compact);
        result.put("count", compact.size());
        result.put("empty_result", compact.isEmpty());
        result.put("resultView", "compact_model_context");
        return result;
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private Map<String, Object> assetResult(Map<String, Object> source, String discoveryId) {
        String dataset = text(source, "dataset_code", "datasetCode");
        String title = text(source, "title", "asset_name", "assetName");
        String description = text(source, "description", "business_description", "businessDescription");
        String storage = text(source, "storage_location", "storageLocation");
        if (storage.isBlank()) {
            String database = text(source, "database_name", "databaseName");
            String table = text(source, "table_name", "tableName");
            storage = database.isBlank() ? table : database + "." + table;
        }
        String database = text(source, "database_name", "databaseName");
        String archiveTable = text(source, "archive_table_name", "archiveTableName");
        String archiveStorage = archiveTable.isBlank() ? ""
            : (database.isBlank() ? archiveTable : database + "." + archiveTable);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("resultType", "financial_data_asset");
        item.put("documentKind", "market_asset_catalog");
        item.put("dataset", dataset);
        item.put("title", title.isBlank() ? dataset : title);
        item.put("snippet", description);
        if (source.get("relevance_score") instanceof Number score) {
            item.put("relevanceScore", score.doubleValue());
        }
        item.put("businessTags", source.getOrDefault("business_tags_json", List.of()));
        item.put("updateFrequency", text(source, "update_frequency", "updateFrequency"));
        item.put("lastObservationDate", text(source, "last_observation_date", "lastObservationDate"));
        item.put("availableFields", financialFields(source.get("fields")));
        item.put("storageLocation", storage);
        item.put("archiveStorageLocation", archiveStorage);
        item.put("retentionPolicy", Map.of(
            "dailyHotDays", numberValue(source, "hot_retention_days", "hotRetentionDays", 7),
            "weeklySnapshotDays", numberValue(source, "archive_retention_days", "archiveRetentionDays", 1825),
            "historyGranularity", text(source, "history_granularity", "historyGranularity")));
        item.put("readTool", FinancialDataMcpToolProvider.TOOL_NAME);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("dataset", dataset);
        evidence.put("storageLocation", storage);
        evidence.put("archiveStorageLocation", archiveStorage);
        evidence.put("catalogIndex", "financial-data-asset");
        item.put("evidence", evidence);
        item.put("followUp", Map.of(
            "tool", FinancialDataMcpToolProvider.TOOL_NAME,
            "arguments", Map.of("dataset", dataset, "discovery_id", discoveryId, "limit", 50)));
        return item;
    }

    private Map<String, Object> financialIndexGuide(List<Map<String, Object>> assets, String discoveryId) {
        Map<String, Object> guide = new LinkedHashMap<>();
        guide.put("name", "financial-data-asset");
        guide.put("contractVersion", "financial_index_capability_v1");
        guide.put("purpose",
            "Search governed financial datasets by business meaning, discover their fields and supported scenarios, "
                + "then explicitly select a dataset before reading authoritative observations.");
        guide.put("searchBehavior",
            "Every web_search call searches the news index first and then this compatible financial index. "
                + "Revise the query when evidence is incomplete; an exact dataset code is optional.");
        guide.put("supportedScenarios", List.of(
            "A-share and exchange-traded instrument price/return/volume lookup",
            "major index close, change, valuation and market breadth review",
            "security master lookup by code or Chinese security name",
            "historical market observations and date-range comparison",
            "financial dataset and field discovery for follow-up analysis"
        ));
        guide.put("matchedDatasets", assets);
        guide.put("matchedDatasetCount", assets.size());
        guide.put("availableFieldsByDataset", assets.stream().collect(java.util.stream.Collectors.toMap(
            asset -> String.valueOf(asset.getOrDefault("dataset", "")),
            asset -> asset.getOrDefault("availableFields", List.of()),
            (left, right) -> left,
            LinkedHashMap::new
        )));
        guide.put("compatibleDirectQuery", false);
        guide.put("secondStage", Map.of(
            "tool", FinancialDataMcpToolProvider.TOOL_NAME,
            "requiredArgument", "dataset",
            "reason", "Financial row reads require explicit governed dataset selection"));
        guide.put("queryRevisionHint",
            "Use the evidence gaps to add the security/index name or code, target metric, event/news topic, "
                + "and requested date or range. Do not change the web_search tool.");
        guide.put("discovery_id", discoveryId);
        return Map.copyOf(guide);
    }

    private List<Map<String, Object>> financialFields(Object rawFields) {
        if (!(rawFields instanceof Iterable<?> fields)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object raw : fields) {
            if (!(raw instanceof Map<?, ?> field)) continue;
            String name = firstNonBlank(fieldText(field, "field_name"), fieldText(field, "fieldName"));
            if (name.isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            String type = firstNonBlank(fieldText(field, "field_type"), fieldText(field, "fieldType"));
            if (!type.isBlank()) item.put("type", type);
            String description = firstNonBlank(
                fieldText(field, "business_description"), fieldText(field, "businessDescription"));
            if (!description.isBlank()) item.put("description", description);
            item.put("exactFilterKey", name);
            if ("STRING".equalsIgnoreCase(type)) item.put("containsFilterKey", name + "_like");
            result.add(Map.copyOf(item));
            if (result.size() >= 40) break;
        }
        return List.copyOf(result);
    }

    private String fieldText(Map<?, ?> field, String key) {
        Object value = field.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private List<Map<String, Object>> interleave(List<Map<String, Object>> first,
                                                  List<Map<String, Object>> second, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; result.size() < limit && (i < first.size() || i < second.size()); i++) {
            if (i < first.size()) result.add(first.get(i));
            if (result.size() < limit && i < second.size()) result.add(second.get(i));
        }
        return result;
    }

    private String text(Map<String, Object> source, String... names) {
        for (String name : names) {
            Object value = source.get(name);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private int bounded(Number value, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, value == null ? fallback : value.intValue()));
    }

    private int numberValue(Map<String, Object> source, String snake, String camel, int fallback) {
        Object value = source.containsKey(snake) ? source.get(snake) : source.get(camel);
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private McpToolDefinition definition(String name, String title, String description,
                                         List<ToolParameter> parameters, boolean callable, int seconds) {
        return new McpToolDefinition(name, title, description, McpCapabilityCodes.NEWS, "chatchat-mcp-server",
            parameters, true, callable, Duration.ofSeconds(seconds));
    }
    private ToolParameter text(String name, String description, boolean required) {
        return ToolParameter.builder().name(name).type("string").description(description).required(required).build();
    }
    private ToolParameter object(String name, String description) {
        return ToolParameter.builder().name(name).type("object").description(description).required(false).build();
    }
    private ToolParameter bool(String name, String description, boolean value) {
        return ToolParameter.builder().name(name).type("boolean").description(description).required(false)
            .defaultValue(value).build();
    }
    private ToolParameter number(String name, String description, int value, int min, int max) {
        return ToolParameter.builder().name(name).type("number").description(description).required(false)
            .defaultValue(value).minimum(min).maximum(max).build();
    }
}
