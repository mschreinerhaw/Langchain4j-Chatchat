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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The only public search tool; it composes independent news and market runtimes. */
@Component
public class RemoteNewsMcpToolProvider implements McpToolProvider {
    private static final Pattern SECURITY_CODE = Pattern.compile("(?<!\\d)([0-9]{6})(?!\\d)");
    private static final Pattern SECURITY_NAME_WITH_CODE = Pattern.compile(
        "(?:^|[\\s,，;；])([\\p{IsHan}A-Za-z*]{2,16})\\s*[（(]\\s*[0-9]{6}\\s*[）)]");
    private static final Pattern SECURITY_NAME_WITH_INTENT = Pattern.compile(
        "^(?:(?:请)?(?:查询|查看|分析))?\\s*([\\p{IsHan}A-Za-z*]{2,12}?)(?:股票)?(?:的)?(?:行情|股价)(?:查询|分析)?$");
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
            "Searches news and financial data assets. Matching financial assets are automatically resolved to compact governed observations; pass dataset explicitly only for a focused follow-up query.",
            List.of(text("query", "News topic, business question, or financial data keywords", false),
                number("num_results", "Maximum number of unified search results to return", 10, 1, 50),
                text("dataset", "Optional dataset code returned by a financial_data_asset result", false),
                object("filters", "Exact-match filters on registered fields, such as securityCode or quoteCode"),
                text("startDate", "Optional observation start date in YYYY-MM-DD", false),
                text("endDate", "Optional observation end date in YYYY-MM-DD", false),
                text("historyMode", "Storage tier: auto (default), daily (7-day hot data), or weekly (snapshots)", false),
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
                return ToolOutput.success(data, "Financial dataset query completed");
            } catch (Exception ex) {
                return ToolOutput.failure(ex);
            }
        }

        String query = input.getParameterAsString("query", "").trim();
        if (query.isBlank()) return ToolOutput.failure("query parameter is required when dataset is absent");
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
            assets = marketCatalog.search(query, limit).stream().map(this::assetResult).toList();
        } catch (Exception ex) {
            warnings.add("market: " + safe(ex.getMessage()));
        }
        if (news.isEmpty() && assets.isEmpty() && warnings.size() == 2) {
            return ToolOutput.failure("Both news and market search are unavailable: " + String.join("; ", warnings));
        }

        List<Map<String, Object>> financialData = hydrateFinancialData(query, assets, input, warnings);
        List<Map<String, Object>> marketResults = new ArrayList<>(financialData);
        marketResults.addAll(assets);
        List<Map<String, Object>> results = interleave(news, marketResults, limit);
        List<String> urls = results.stream().map(item -> item.get("url")).filter(String.class::isInstance)
            .map(String.class::cast).filter(value -> !value.isBlank()).distinct().toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("provider", "chatchat-unified-search");
        data.put("mode", "unified_news_and_financial_asset_search");
        data.put("count", results.size());
        data.put("newsCount", news.size());
        data.put("financialAssetCount", assets.size());
        data.put("financialDatasetCount", financialData.size());
        data.put("financialObservationCount", financialData.stream()
            .mapToInt(item -> ((Number) item.getOrDefault("count", 0)).intValue()).sum());
        data.put("financialData", financialData);
        data.put("results", results);
        data.put("reference_urls", urls);
        if (!warnings.isEmpty()) data.put("warnings", warnings);
        return ToolOutput.success(data, "Unified search completed");
    }

    private List<Map<String, Object>> hydrateFinancialData(String query, List<Map<String, Object>> assets,
                                                            ToolInput input, List<String> warnings) {
        List<String> datasets = relevantDatasets(query, assets);
        if (datasets.isEmpty()) return List.of();
        LocalDate startDate = date(input.getParameterAsString("startDate", ""));
        LocalDate endDate = date(input.getParameterAsString("endDate", ""));
        String historyMode = input.getParameterAsString("historyMode", "auto");
        int rowLimit = bounded(input.getParameterAsNumber("limit"), 10, 1, 20);
        List<Map<String, Object>> hydrated = new ArrayList<>();
        for (String dataset : datasets) {
            try {
                List<Map<String, Object>> rows = new ArrayList<>();
                List<Map<String, Object>> filters = entityFilters(query, dataset);
                if (filters.isEmpty()) {
                    rows.addAll(rows(marketStore.query(dataset, Map.of(), startDate, endDate, rowLimit, historyMode)));
                } else {
                    for (Map<String, Object> filter : filters) {
                        rows.addAll(rows(marketStore.query(dataset, filter, startDate, endDate,
                            Math.min(3, rowLimit), historyMode)));
                    }
                }
                List<Map<String, Object>> compactRows = compactRows(rows, rowLimit);
                if (compactRows.isEmpty()) {
                    if (!filters.isEmpty()) warnings.add("dataset " + dataset
                        + ": no observations matched exact filters " + filters);
                    continue;
                }
                Map<String, Object> asset = assets.stream()
                    .filter(item -> dataset.equals(item.get("dataset"))).findFirst().orElse(Map.of());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("resultType", "financial_data");
                item.put("documentKind", "market_observations");
                item.put("dataset", dataset);
                item.put("title", firstNonBlank(String.valueOf(asset.getOrDefault("title", "")), dataset)
                    + "：实际观测数据");
                item.put("snippet", "已从受治理存储位置读取实际金融数据，不是资产目录元数据。");
                item.put("count", compactRows.size());
                item.put("rows", compactRows);
                if (!filters.isEmpty()) item.put("appliedFilters", filters);
                item.put("historyMode", historyMode);
                hydrated.add(item);
            } catch (Exception ex) {
                warnings.add("dataset " + dataset + ": " + safe(ex.getMessage()));
            }
        }
        return List.copyOf(hydrated);
    }

    private List<String> relevantDatasets(String query, List<Map<String, Object>> assets) {
        Set<String> available = new LinkedHashSet<>();
        for (Map<String, Object> asset : assets) {
            String dataset = String.valueOf(asset.getOrDefault("dataset", "")).trim();
            if (!dataset.isBlank()) available.add(dataset);
        }
        if (available.isEmpty()) return List.of();
        String normalized = query == null ? "" : query.toLowerCase();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        List<String> securityCodes = securityCodes(query);
        if (!securityCodes.isEmpty() && containsAny(normalized, "行情", "股价", "价格", "涨跌", "成交", "股票", "a股")) {
            addAvailable(selected, available, "market_quote_daily");
        } else if (containsAny(normalized, "a股", "股票", "行情", "股价", "大盘", "指数", "上证", "深证", "沪深", "创业板", "科创")) {
            addAvailable(selected, available, "market_quote_daily", "index_valuation_daily", "market_statistics_daily");
        }
        if (!securityCodes.isEmpty() && containsAny(normalized, "估值", "市盈率", "市净率", "pe", "pb")) {
            addAvailable(selected, available, "stock_valuation_daily");
        }
        if (containsAny(normalized, "融资", "融券", "两融")) addAvailable(selected, available, "margin_trade_daily");
        if (containsAny(normalized, "分红", "送股", "转增", "配股")) addAvailable(selected, available, "stock_dividend_event");
        if (containsAny(normalized, "etf", "基金规模", "基金份额")) addAvailable(selected, available, "etf_scale_daily");
        if (containsAny(normalized, "债券", "中债", "收益率", "回购", "结算", "担保品")) {
            addAvailable(selected, available, "bond_yield_curve_daily", "bond_market_daily",
                "bond_settlement_daily", "bond_collateral_monthly", "bond_counter_quote_daily");
        }
        if (selected.isEmpty()) available.stream().limit(2).forEach(selected::add);
        return selected.stream().limit(5).toList();
    }

    private List<Map<String, Object>> entityFilters(String query, String dataset) {
        if (query == null || query.isBlank()) return List.of();
        Map<String, String> codes = new LinkedHashMap<>();
        codes.put("上证指数", "000001");
        codes.put("沪深300", "000300");
        codes.put("上证50", "000016");
        codes.put("科创50", "000688");
        codes.put("科创综指", "000680");
        if ("market_quote_daily".equals(dataset)) {
            codes.put("深证成指", "399001");
            codes.put("创业板指", "399006");
            codes.put("深证100", "399330");
            codes.put("创业板50", "399673");
        }
        String field = "index_valuation_daily".equals(dataset) ? "indexCode"
            : "market_quote_daily".equals(dataset) ? "quoteCode"
            : Set.of("stock_valuation_daily", "margin_trade_daily", "stock_dividend_event").contains(dataset)
                ? "securityCode" : "";
        if (field.isBlank()) return List.of();
        List<Map<String, Object>> filters = new ArrayList<>();
        for (String code : securityCodes(query)) filters.add(Map.of(field, code));
        if ("market_quote_daily".equals(dataset)) {
            for (String name : securityNames(query)) filters.add(Map.of("quoteNameLike", name));
        }
        codes.forEach((name, code) -> {
            if (query.contains(name) && filters.stream().noneMatch(item -> code.equals(item.get(field)))) {
                filters.add(Map.of(field, code));
            }
        });
        return filters;
    }

    private List<String> securityCodes(String query) {
        if (query == null || query.isBlank()) return List.of();
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        Matcher matcher = SECURITY_CODE.matcher(query);
        while (matcher.find()) codes.add(matcher.group(1));
        return List.copyOf(codes);
    }

    private List<String> securityNames(String query) {
        if (query == null || query.isBlank()) return List.of();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher withCode = SECURITY_NAME_WITH_CODE.matcher(query.trim());
        while (withCode.find()) addSecurityName(names, withCode.group(1));
        if (names.isEmpty()) {
            Matcher withIntent = SECURITY_NAME_WITH_INTENT.matcher(query.trim());
            if (withIntent.matches()) addSecurityName(names, withIntent.group(1));
        }
        return List.copyOf(names);
    }

    private void addSecurityName(Set<String> names, String candidate) {
        String name = candidate == null ? "" : candidate.trim();
        if (name.length() < 2 || Set.of("A股", "股票", "行情", "股价", "大盘", "指数", "今日", "实时", "最新")
            .contains(name)) return;
        names.add(name);
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

    private void addAvailable(Set<String> target, Set<String> available, String... datasets) {
        for (String dataset : datasets) if (available.contains(dataset)) target.add(dataset);
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resultList(Object data) {
        if (!(data instanceof Map<?, ?> map) || !(map.get("results") instanceof List<?> values)) return List.of();
        return values.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).toList();
    }

    private Map<String, Object> assetResult(Map<String, Object> source) {
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
        return item;
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
