package com.chatchat.mcpserver.news;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.runtime.mcp.registry.McpCapabilityCodes;
import com.chatchat.runtime.mcp.registry.McpToolDefinition;
import com.chatchat.runtime.mcp.registry.McpToolExecutor;
import com.chatchat.runtime.mcp.registry.McpToolProvider;
import com.chatchat.runtime.market.storage.FinancialAssetCatalogService;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The only public search tool; it composes independent news and market runtimes. */
@Component
public class RemoteNewsMcpToolProvider implements McpToolProvider {
    private static final Pattern QUERY_DATE = Pattern.compile(
        "(?<!\\d)(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})(?:日)?");
    private final NewsRuntimeClient newsClient;
    private final FinancialAssetCatalogService marketCatalog;
    private final FinancialDataStore marketStore;
    private final Map<String, McpToolDefinition> definitions;

    public RemoteNewsMcpToolProvider(NewsRuntimeClient newsClient, FinancialAssetCatalogService marketCatalog,
                                     FinancialDataStore marketStore) {
        this.newsClient = newsClient;
        this.marketCatalog = marketCatalog;
        this.marketStore = marketStore;
        McpToolDefinition webSearch = definition("web_search", "Unified Web Search",
            "Unified one-call search for news and governed financial data. The tool searches the compatible "
                + "financial-data-asset index, directly reads observations from the highest-ranked matched datasets, "
                + "and returns authoritative rows together with news. The optional dataset parameter remains available "
                + "for callers that already know the exact dataset code.",
            List.of(text("query", "News topic, business question, or financial data keywords", false),
                number("num_results", "Maximum number of unified search results to return", 10, 1, 50),
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
        String dataset = input.getParameterAsString("dataset", "").trim();
        if (!dataset.isBlank()) {
            try {
                Map<String, Object> filters = input.getParameter("filters") instanceof Map<?, ?> values
                    ? values.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        entry -> String.valueOf(entry.getKey()), Map.Entry::getValue)) : Map.of();
                java.time.LocalDate startDate = date(input.getParameterAsString("startDate", ""));
                java.time.LocalDate endDate = date(input.getParameterAsString("endDate", ""));
                String historyMode = input.getParameterAsString("historyMode", "auto");
                int rowLimit = bounded(input.getParameterAsNumber("limit"), 50, 1, 200);
                Map<String, Object> data = new LinkedHashMap<>(marketStore.query(dataset, filters,
                    startDate, endDate, rowLimit, historyMode));
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
                return ToolOutput.failure(ex);
            }
        }

        String query = input.getParameterAsString("query", "").trim();
        if (query.isBlank()) return ToolOutput.failure("query parameter is required when dataset is absent");
        String financialAssetQuery = marketStore.assetSearchQuery(query, 10);
        if (financialAssetQuery == null || financialAssetQuery.isBlank()) financialAssetQuery = query;
        String discoveryId = UUID.randomUUID().toString();
        int limit = bounded(input.getParameterAsNumber("num_results"), 10, 1, 50);
        List<Map<String, Object>> news = List.of();
        List<Map<String, Object>> assets = List.of();
        List<String> warnings = new ArrayList<>();
        try {
            ToolOutput output = newsClient.invoke("web_search", input);
            if (output.isSuccess()) news = resultList(output.getData());
            else warnings.add("news: " + safe(output.getErrorMessage()));
        } catch (Exception ex) {
            warnings.add("news: " + safe(ex.getMessage()));
        }
        try {
            String chainId = discoveryId;
            assets = marketCatalog.search(financialAssetQuery, limit).stream()
                .map(source -> assetResult(source, chainId)).toList();
        } catch (Exception ex) {
            warnings.add("market: " + safe(ex.getMessage()));
        }
        if (news.isEmpty() && assets.isEmpty() && warnings.size() == 2) {
            return ToolOutput.failure("Both news and market search are unavailable: " + String.join("; ", warnings));
        }

        List<Map<String, Object>> financialData = hydrateCompatibleFinancialIndex(
            query, assets, input, warnings);
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
        data.put("retrieval_stage", "COMPATIBLE_QUERY");
        data.put("sample_only", false);
        data.put("requires_second_query", false);
        data.put("provider", "chatchat-unified-search");
        data.put("mode", "unified_news_and_compatible_financial_index_search");
        data.put("count", results.size());
        data.put("newsCount", news.size());
        data.put("financialAssetCount", assets.size());
        data.put("financialAssets", assets);
        data.put("financialIndex", financialIndexGuide(assets, discoveryId));
        data.put("financialDatasetCount", financialData.size());
        data.put("financialObservationCount", financialData.stream()
            .mapToInt(item -> ((Number) item.getOrDefault("count", 0)).intValue()).sum());
        data.put("financialData", financialData);
        data.put("results", results);
        data.put("reference_urls", urls);
        if (!warnings.isEmpty()) data.put("warnings", warnings);
        ToolOutput result = ToolOutput.success(data, "Unified news and compatible financial index search completed");
        result.getMetadata().put("financialRetrievalStage", "COMPATIBLE_QUERY");
        result.getMetadata().put("financialDiscoveryId", discoveryId);
        result.getMetadata().put("financialCandidateDatasetCount", assets.size());
        result.getMetadata().put("financialQueriedDatasetCount", financialData.size());
        result.getMetadata().put("financialSecondQueryRequired", false);
        return result;
    }

    private List<Map<String, Object>> hydrateCompatibleFinancialIndex(
        String query, List<Map<String, Object>> assets, ToolInput input, List<String> warnings
    ) {
        if (assets.isEmpty()) return List.of();
        LocalDate explicitStart = date(input.getParameterAsString("startDate", ""));
        LocalDate explicitEnd = date(input.getParameterAsString("endDate", ""));
        LocalDate queryDate = queryDate(query);
        LocalDate startDate = explicitStart == null ? queryDate : explicitStart;
        LocalDate endDate = explicitEnd == null ? queryDate : explicitEnd;
        String historyMode = input.getParameterAsString("historyMode", "auto");
        int rowLimit = bounded(input.getParameterAsNumber("limit"), 20, 1, 50);
        List<Map<String, Object>> hydrated = new ArrayList<>();
        for (Map<String, Object> asset : assets.stream().limit(5).toList()) {
            String dataset = String.valueOf(asset.getOrDefault("dataset", "")).trim();
            if (dataset.isBlank()) continue;
            try {
                List<Map<String, Object>> filters = marketStore.resolveEntityFilters(dataset, query, 5);
                List<Map<String, Object>> foundRows = new ArrayList<>();
                if (filters.isEmpty()) {
                    foundRows.addAll(rows(marketStore.query(
                        dataset, Map.of(), startDate, endDate, rowLimit, historyMode)));
                } else {
                    int perEntityLimit = Math.max(1, rowLimit / filters.size());
                    for (Map<String, Object> filter : filters) {
                        foundRows.addAll(rows(marketStore.query(
                            dataset, filter, startDate, endDate, perEntityLimit, historyMode)));
                    }
                }
                List<Map<String, Object>> compactRows = compactRows(foundRows, rowLimit);
                if (compactRows.isEmpty()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("resultType", "financial_data");
                item.put("documentKind", "market_observations");
                item.put("dataset", dataset);
                item.put("title", firstNonBlank(String.valueOf(asset.getOrDefault("title", "")), dataset)
                    + "：实际观测数据");
                item.put("snippet", "已直接从兼容金融索引命中的受治理数据集读取实际观测值。");
                item.put("count", compactRows.size());
                item.put("rows", compactRows);
                if (!filters.isEmpty()) item.put("appliedFilters", filters);
                if (startDate != null) item.put("startDate", startDate.toString());
                if (endDate != null) item.put("endDate", endDate.toString());
                item.put("historyMode", historyMode);
                hydrated.add(Map.copyOf(item));
            } catch (Exception ex) {
                warnings.add("dataset " + dataset + ": " + safe(ex.getMessage()));
            }
        }
        return List.copyOf(hydrated);
    }

    private LocalDate queryDate(String query) {
        if (query == null || query.isBlank()) return null;
        Matcher matcher = QUERY_DATE.matcher(query);
        if (!matcher.find()) return null;
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
        } catch (RuntimeException ignored) {
            return null;
        }
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

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resultList(Object data) {
        if (!(data instanceof Map<?, ?> map) || !(map.get("results") instanceof List<?> values)) return List.of();
        return values.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).toList();
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
        item.put("readTool", "web_search");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("dataset", dataset);
        evidence.put("storageLocation", storage);
        evidence.put("archiveStorageLocation", archiveStorage);
        evidence.put("catalogIndex", "financial-data-asset");
        item.put("evidence", evidence);
        item.put("followUp", Map.of(
            "tool", "web_search",
            "arguments", Map.of("dataset", dataset, "discovery_id", discoveryId, "limit", 50)));
        return item;
    }

    private Map<String, Object> financialIndexGuide(List<Map<String, Object>> assets, String discoveryId) {
        Map<String, Object> guide = new LinkedHashMap<>();
        guide.put("name", "financial-data-asset");
        guide.put("contractVersion", "financial_index_capability_v1");
        guide.put("purpose",
            "Search governed financial datasets by business meaning, discover their fields and supported scenarios, "
                + "and directly read authoritative observations from compatible matched datasets.");
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
        guide.put("compatibleDirectQuery", true);
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

    private java.time.LocalDate date(String value) {
        return value == null || value.isBlank() ? null : java.time.LocalDate.parse(value.trim());
    }

    private String safe(String value) { return value == null || value.isBlank() ? "unavailable" : value; }

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
    private ToolParameter number(String name, String description, int value, int min, int max) {
        return ToolParameter.builder().name(name).type("number").description(description).required(false)
            .defaultValue(value).minimum(min).maximum(max).build();
    }
}
