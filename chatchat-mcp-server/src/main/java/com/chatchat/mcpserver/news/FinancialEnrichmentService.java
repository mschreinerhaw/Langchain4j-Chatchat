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
        // Discovery and execution are deliberately separate. An implicit web search
        // may discover governed datasets, but only an explicit dataset call is allowed
        // to consume analytical query capacity.
        return new EnrichmentResult(assetQuery, assets, List.of(), List.copyOf(warnings),
            assets.isEmpty() ? null : "explicit_dataset_required");
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
