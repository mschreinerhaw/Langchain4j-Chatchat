package com.chatchat.mcpserver.news;

import com.chatchat.common.concurrent.CancellationSupport;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.runtime.market.storage.FinancialAssetCatalogService;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional, independently testable financial enrichment composed behind web search. */
@Service
public class FinancialEnrichmentService {
    public static final String FINAL_SUMMARY_PURPOSE = "final_summary_web_enhancement";
    private static final Pattern EXPLICIT_DATE = Pattern.compile(
        "(?<!\\d)(\\d{4})(?:年|[-/.])(\\d{1,2})(?:月|[-/.])(\\d{1,2})(?:日)?(?!\\d)");

    private final FinancialAssetCatalogService catalog;
    private final FinancialDataStore store;
    private final FinancialQueryCacheService queryCache;

    public FinancialEnrichmentService(FinancialAssetCatalogService catalog, FinancialDataStore store) {
        this(catalog, store, null);
    }

    @Autowired
    public FinancialEnrichmentService(FinancialAssetCatalogService catalog, FinancialDataStore store,
                                      FinancialQueryCacheService queryCache) {
        this.catalog = catalog;
        this.store = store;
        this.queryCache = queryCache;
    }

    public EnrichmentResult enrich(String query, ToolInput input, int limit) {
        if (!needsFinancialEnrichment(input)) {
            return new EnrichmentResult(query, List.of(), List.of(), List.of(), "runtime_context_disabled");
        }
        List<String> warnings = new ArrayList<>();
        String retrievalIntent = financialIntentQuery(query, input);
        String assetQuery = retrievalIntent;
        try {
            assetQuery = store.assetSearchQuery(retrievalIntent, 10);
            if (assetQuery == null || assetQuery.isBlank()) assetQuery = retrievalIntent;
        } catch (Exception ex) {
            CancellationSupport.rethrowIfCancelled(ex, "financial asset query normalization");
            warnings.add("market normalization: " + safe(ex.getMessage()));
        }
        List<Map<String, Object>> assets;
        try {
            assets = catalog.search(assetQuery, limit);
            assets = (assets == null ? List.<Map<String, Object>>of() : assets).stream()
                .limit(Math.max(1, limit)).toList();
        } catch (Exception ex) {
            CancellationSupport.rethrowIfCancelled(ex, "financial asset search");
            warnings.add("market: " + safe(ex.getMessage()));
            assets = List.of();
        }
        if (assets.isEmpty()) {
            return new EnrichmentResult(assetQuery, assets, List.of(), List.copyOf(warnings), null);
        }

        int datasetLimit = bounded(input.getParameterAsNumber("financial_dataset_limit"), 2, 1, 3);
        int rowLimit = bounded(input.getParameterAsNumber("financial_row_limit"), 20, 1, 50);
        DateRange dateRange = dateRange(input, retrievalIntent, query);
        LocalDate startDate = dateRange.startDate();
        LocalDate endDate = dateRange.endDate();
        String historyMode = input.getParameterAsString("historyMode", "auto");
        List<Map<String, Object>> financialData = new ArrayList<>();
        int candidateLimit = Math.min(assets.size(), Math.max(datasetLimit, datasetLimit * 2));
        int attempted = 0;
        for (Map<String, Object> asset : assets) {
            if (financialData.size() >= datasetLimit || attempted >= candidateLimit) break;
            String dataset = text(asset, "dataset_code", "datasetCode");
            if (dataset.isBlank()) continue;
            attempted++;
            try {
                List<Map<String, Object>> resolved = store.resolveEntityFilters(dataset, query, 5);
                Map<String, Object> filters = resolved.isEmpty() ? Map.of() : resolved.get(0);
                Map<String, Object> result = new java.util.LinkedHashMap<>(cachedQuery(
                    dataset, filters, startDate, endDate, rowLimit, historyMode, input));
                result.put("dataset", dataset);
                result.put("resultType", "financial_dataset_query");
                result.put("retrievalSource", "governed_financial_store");
                result.put("filters", filters);
                if (hasObservations(result)) {
                    financialData.add(result);
                } else {
                    warnings.add("financial dataset " + dataset + ": no matching observations");
                }
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
        DateRange dateRange = dateRange(input, input.getParameterAsString("query", ""));
        LocalDate startDate = dateRange.startDate();
        LocalDate endDate = dateRange.endDate();
        String historyMode = input.getParameterAsString("historyMode", "auto");
        int rowLimit = bounded(input.getParameterAsNumber("limit"), 50, 1, 200);
        return cachedQuery(dataset, filters, startDate, endDate, rowLimit, historyMode, input);
    }

    private Map<String, Object> cachedQuery(String dataset, Map<String, Object> filters,
                                            LocalDate startDate, LocalDate endDate, int rowLimit,
                                            String historyMode, ToolInput input) {
        if (queryCache == null) {
            return store.query(dataset, filters, startDate, endDate, rowLimit, historyMode);
        }
        return queryCache.getOrLoad(dataset, filters, startDate, endDate, rowLimit, historyMode,
            tenantScope(input),
            () -> store.query(dataset, filters, startDate, endDate, rowLimit, historyMode));
    }

    private String tenantScope(ToolInput input) {
        if (input == null) return "";
        String direct = textValue(input.getParameter("tenantId"));
        if (!direct.isBlank()) return direct;
        direct = textValue(input.getContext() == null ? null : input.getContext().get("tenantId"));
        if (!direct.isBlank()) return direct;
        Object mcpContext = input.getParameter("mcpContext");
        if (mcpContext instanceof Map<?, ?> context) {
            direct = textValue(context.get("tenantId"));
            if (!direct.isBlank()) return direct;
            Object tenant = context.get("tenant");
            if (tenant instanceof Map<?, ?> tenantMap) {
                direct = textValue(tenantMap.get("tenantId"));
                if (!direct.isBlank()) return direct;
            }
        }
        return "";
    }

    private String textValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    boolean needsFinancialEnrichment(ToolInput input) {
        Object purpose = input == null || input.getContext() == null
            ? null : input.getContext().get("internalPurpose");
        return !FINAL_SUMMARY_PURPOSE.equals(String.valueOf(purpose))
            || input.getParameterAsBoolean("financial_data_required", false);
    }

    private String financialIntentQuery(String stepQuery, ToolInput input) {
        if (input != null && input.getParameterAsBoolean("financial_data_required", false)
            && input.getContext() != null) {
            Object configured = input.getContext().get("financialIntentQuery");
            if (configured != null && !String.valueOf(configured).isBlank()) {
                return String.valueOf(configured).trim();
            }
        }
        return stepQuery == null ? "" : stepQuery.trim();
    }

    private boolean hasObservations(Map<String, Object> result) {
        if (result == null || result.isEmpty()) return false;
        Object rows = result.get("rows");
        if (rows instanceof Iterable<?> iterable && iterable.iterator().hasNext()) return true;
        Object count = result.get("count");
        return count instanceof Number number && number.longValue() > 0L;
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

    /**
     * Keeps explicit tool arguments authoritative and fills only missing bounds from an
     * unambiguous day-level date in the retrieval intent. This prevents a dated search
     * from accidentally scanning the complete financial retention window.
     */
    private DateRange dateRange(ToolInput input, String... queryCandidates) {
        LocalDate startDate = date(input == null ? "" : input.getParameterAsString("startDate", ""));
        LocalDate endDate = date(input == null ? "" : input.getParameterAsString("endDate", ""));
        if (startDate != null || endDate != null) return ordered(startDate, endDate);
        LocalDate inferred = firstExplicitDate(queryCandidates);
        if (startDate == null) startDate = inferred;
        if (endDate == null) endDate = inferred;
        return ordered(startDate, endDate);
    }

    private LocalDate firstExplicitDate(String... candidates) {
        if (candidates == null) return null;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            Matcher matcher = EXPLICIT_DATE.matcher(candidate);
            if (!matcher.find()) continue;
            try {
                return LocalDate.of(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
            } catch (DateTimeException | NumberFormatException ignored) {
                // Invalid date-like text remains lexical input and must not broaden the SQL query.
            }
        }
        return null;
    }

    private DateRange ordered(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            return new DateRange(endDate, startDate);
        }
        return new DateRange(startDate, endDate);
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

    private record DateRange(LocalDate startDate, LocalDate endDate) { }
}
