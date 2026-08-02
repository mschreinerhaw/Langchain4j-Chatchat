package com.chatchat.mcpserver.news;

import com.chatchat.common.concurrent.CancellationSupport;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.runtime.market.storage.FinancialAssetCatalogService;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
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

    public EnrichmentResult enrich(String query, ToolInput input, int limit) {
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
        boolean dataRequired = input != null
            && input.getParameterAsBoolean("financial_data_required", false);
        if (!dataRequired || assets.isEmpty()) {
            return new EnrichmentResult(assetQuery, assets, List.of(), List.copyOf(warnings),
                assets.isEmpty() ? null : "explicit_dataset_required");
        }

        int datasetLimit = bounded(input.getParameterAsNumber("financial_dataset_limit"), 2, 1, 3);
        int rowLimit = bounded(input.getParameterAsNumber("financial_row_limit"), 20, 1, 50);
        LocalDate startDate = date(input.getParameterAsString("startDate", ""));
        LocalDate endDate = date(input.getParameterAsString("endDate", ""));
        String historyMode = input.getParameterAsString("historyMode", "auto");
        List<Map<String, Object>> financialData = new ArrayList<>();
        for (Map<String, Object> asset : assets.stream().limit(datasetLimit).toList()) {
            String dataset = text(asset, "dataset_code", "datasetCode");
            if (dataset.isBlank()) continue;
            try {
                List<Map<String, Object>> resolved = store.resolveEntityFilters(dataset, query, 5);
                Map<String, Object> filters = resolved.isEmpty() ? Map.of() : resolved.get(0);
                Map<String, Object> result = new java.util.LinkedHashMap<>(
                    store.query(dataset, filters, startDate, endDate, rowLimit, historyMode));
                result.put("dataset", dataset);
                result.put("resultType", "financial_dataset_query");
                result.put("retrievalSource", "governed_financial_store");
                result.put("filters", filters);
                financialData.add(result);
            } catch (Exception ex) {
                CancellationSupport.rethrowIfCancelled(ex, "explicit financial enrichment");
                warnings.add("financial dataset " + dataset + ": " + safe(ex.getMessage()));
            }
        }
        return new EnrichmentResult(assetQuery, assets, List.copyOf(financialData), List.copyOf(warnings),
            financialData.isEmpty() ? "financial_data_unavailable" : null);
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
        return !FINAL_SUMMARY_PURPOSE.equals(String.valueOf(purpose))
            || input.getParameterAsBoolean("financial_data_required", false);
    }

    private String text(Map<String, Object> source, String... names) {
        if (source == null) return "";
        for (String name : names) {
            Object value = source.get(name);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
        }
        return "";
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
