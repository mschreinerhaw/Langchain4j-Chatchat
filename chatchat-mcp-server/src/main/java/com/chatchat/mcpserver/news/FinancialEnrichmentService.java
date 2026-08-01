package com.chatchat.mcpserver.news;

import com.chatchat.common.concurrent.CancellationSupport;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.runtime.market.storage.FinancialAssetCatalogService;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional, independently testable financial enrichment composed behind web search. */
@Service
public class FinancialEnrichmentService {
    public static final String FINAL_SUMMARY_PURPOSE = "final_summary_web_enhancement";

    private final FinancialAssetCatalogService catalog;
    private final FinancialDataStore store;

    public FinancialEnrichmentService(FinancialAssetCatalogService catalog, FinancialDataStore store) {
        this.catalog = catalog;
        this.store = store;
    }

    public EnrichmentResult enrich(String query, ToolInput input, int limit, LocalDate queryDate) {
        if (!needsFinancialEnrichment(input)) {
            return new EnrichmentResult(query, List.of(), List.of(), List.of(), "runtime_context_disabled");
        }
        List<String> warnings = new ArrayList<>();
        String assetQuery = query;
        try {
            assetQuery = store.assetSearchQuery(query, 10);
            if (assetQuery == null || assetQuery.isBlank()) assetQuery = query;
        } catch (Exception ex) {
            CancellationSupport.rethrowIfCancelled(ex, "financial asset query normalization");
            warnings.add("market normalization: " + safe(ex.getMessage()));
        }
        List<Map<String, Object>> assets;
        try {
            assets = catalog.search(assetQuery, limit);
        } catch (Exception ex) {
            CancellationSupport.rethrowIfCancelled(ex, "financial asset search");
            warnings.add("market: " + safe(ex.getMessage()));
            assets = List.of();
        }
        List<Map<String, Object>> data = hydrate(query, assets, input, queryDate, warnings);
        return new EnrichmentResult(assetQuery, assets, data, List.copyOf(warnings), null);
    }

    public Map<String, Object> queryDataset(String dataset, ToolInput input) {
        Map<String, Object> filters = input.getParameter("filters") instanceof Map<?, ?> values
            ? values.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                entry -> String.valueOf(entry.getKey()), Map.Entry::getValue)) : Map.of();
        LocalDate startDate = date(input.getParameterAsString("startDate", ""));
        LocalDate endDate = date(input.getParameterAsString("endDate", ""));
        String historyMode = input.getParameterAsString("historyMode", "auto");
        int rowLimit = bounded(input.getParameterAsNumber("limit"), 50, 1, 200);
        return store.query(dataset, filters, startDate, endDate, rowLimit, historyMode);
    }

    boolean needsFinancialEnrichment(ToolInput input) {
        Object purpose = input == null || input.getContext() == null
            ? null : input.getContext().get("internalPurpose");
        return !FINAL_SUMMARY_PURPOSE.equals(String.valueOf(purpose));
    }

    private List<Map<String, Object>> hydrate(String query, List<Map<String, Object>> assets, ToolInput input,
                                               LocalDate queryDate, List<String> warnings) {
        if (assets.isEmpty()) return List.of();
        LocalDate explicitStart = date(input.getParameterAsString("startDate", ""));
        LocalDate explicitEnd = date(input.getParameterAsString("endDate", ""));
        LocalDate startDate = explicitStart == null ? queryDate : explicitStart;
        LocalDate endDate = explicitEnd == null ? queryDate : explicitEnd;
        String historyMode = input.getParameterAsString("historyMode", "auto");
        int rowLimit = bounded(input.getParameterAsNumber("limit"), 20, 1, 50);
        List<Map<String, Object>> hydrated = new ArrayList<>();
        for (Map<String, Object> asset : assets.stream().limit(5).toList()) {
            CancellationSupport.throwIfCancelled("financial index hydration");
            String dataset = String.valueOf(asset.getOrDefault("dataset_code", asset.getOrDefault("dataset", ""))).trim();
            if (dataset.isBlank()) continue;
            try {
                List<Map<String, Object>> filters = store.resolveEntityFilters(dataset, query, 5);
                List<Map<String, Object>> foundRows = new ArrayList<>();
                if (filters.isEmpty()) {
                    foundRows.addAll(rows(store.query(dataset, Map.of(), startDate, endDate, rowLimit, historyMode)));
                } else {
                    int perEntityLimit = Math.max(1, rowLimit / filters.size());
                    for (Map<String, Object> filter : filters) {
                        CancellationSupport.throwIfCancelled("financial index hydration");
                        foundRows.addAll(rows(store.query(
                            dataset, filter, startDate, endDate, perEntityLimit, historyMode)));
                    }
                }
                List<Map<String, Object>> compactRows = compactRows(foundRows, rowLimit);
                if (compactRows.isEmpty()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("resultType", "financial_data");
                item.put("documentKind", "market_observations");
                item.put("dataset", dataset);
                item.put("title", String.valueOf(asset.getOrDefault("asset_name", dataset)));
                item.put("snippet", "Governed observations from the matched financial dataset");
                item.put("count", compactRows.size());
                item.put("rows", compactRows);
                if (!filters.isEmpty()) item.put("appliedFilters", filters);
                if (startDate != null) item.put("startDate", startDate.toString());
                if (endDate != null) item.put("endDate", endDate.toString());
                item.put("historyMode", historyMode);
                hydrated.add(Map.copyOf(item));
            } catch (Exception ex) {
                CancellationSupport.rethrowIfCancelled(ex, "financial index hydration");
                warnings.add("dataset " + dataset + ": " + safe(ex.getMessage()));
            }
        }
        return List.copyOf(hydrated);
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
                if (value == null || (value instanceof String text && text.isBlank())) return;
                if ("payload_json".equals(normalized) || normalized.endsWith("_history")
                    || (value instanceof String text && text.length() > 4000)) omitted.add(key);
                else row.put(key, value);
            });
            if (!omitted.isEmpty()) row.put("_omitted_fields", List.copyOf(omitted));
            String key = String.valueOf(source.getOrDefault("record_key", row.hashCode())) + "|"
                + source.getOrDefault("observation_date", "");
            unique.putIfAbsent(key, row);
            if (unique.size() >= limit) break;
        }
        return List.copyOf(unique.values());
    }

    private LocalDate date(String value) {
        try { return value == null || value.isBlank() ? null : LocalDate.parse(value.trim()); }
        catch (RuntimeException ignored) { return null; }
    }

    private int bounded(Number value, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, value == null ? fallback : value.intValue()));
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown failure" : value;
    }

    public record EnrichmentResult(String assetQuery, List<Map<String, Object>> assets,
                                   List<Map<String, Object>> financialData, List<String> warnings,
                                   String skippedReason) { }
}
