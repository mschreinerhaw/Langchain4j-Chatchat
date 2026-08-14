package com.chatchat.mcpserver.news;

import com.chatchat.common.concurrent.CancellationSupport;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.runtime.market.storage.FinancialAssetCatalogService;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Optional, independently testable financial enrichment composed behind web search. */
@Service
public class FinancialEnrichmentService {
    public static final String FINAL_SUMMARY_PURPOSE = "final_summary_web_enhancement";

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
            boolean expanded = assets != null && !assets.isEmpty()
                && bestCatalogTextMatch(assetQuery, assets) < 2;
            if (expanded) {
                int expandedLimit = Math.max(limit, Math.min(24, Math.max(1, limit) * 4));
                List<Map<String, Object>> expandedAssets = mergeAssets(
                    assets, catalog.search(assetQuery, expandedLimit));
                if (expandedAssets.size() > assets.size()) {
                    assets = rankAssetsByCatalogText(assetQuery, expandedAssets);
                }
            }
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
        LocalDate startDate = date(input.getParameterAsString("startDate", ""));
        LocalDate endDate = date(input.getParameterAsString("endDate", ""));
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

    /**
     * Generic second-stage catalog ranking. It only compares the request with indexed asset
     * metadata and deliberately contains no market, product, dataset or industry vocabulary.
     */
    private List<Map<String, Object>> rankAssetsByCatalogText(String query, List<Map<String, Object>> assets) {
        if (assets == null || assets.size() < 2) {
            return assets == null ? List.of() : List.copyOf(assets);
        }
        Set<String> queryTokens = lexicalTokens(query);
        if (queryTokens.isEmpty()) return List.copyOf(assets);
        List<Map<String, Object>> ranked = new ArrayList<>(assets);
        // List.sort is stable, so the index score/order remains the tie-breaker.
        ranked.sort(Comparator.comparingInt(
            (Map<String, Object> asset) -> catalogTextMatch(queryTokens, asset)).reversed());
        return List.copyOf(ranked);
    }

    private int bestCatalogTextMatch(String query, List<Map<String, Object>> assets) {
        Set<String> queryTokens = lexicalTokens(query);
        return assets == null ? 0 : assets.stream()
            .mapToInt(asset -> catalogTextMatch(queryTokens, asset)).max().orElse(0);
    }

    private int catalogTextMatch(Set<String> queryTokens, Map<String, Object> asset) {
        if (queryTokens.isEmpty() || asset == null) return 0;
        String catalogText = text(asset, "dataset_code", "datasetCode") + " "
            + text(asset, "asset_name", "assetName") + " "
            + text(asset, "business_description", "businessDescription") + " "
            + text(asset, "business_tags_json", "businessTags") + " "
            + String.valueOf(asset.getOrDefault("fields", ""));
        Set<String> catalogTokens = lexicalTokens(catalogText);
        int matches = 0;
        for (String token : queryTokens) if (catalogTokens.contains(token)) matches++;
        return matches;
    }

    private List<Map<String, Object>> mergeAssets(List<Map<String, Object>> first,
                                                   List<Map<String, Object>> second) {
        Map<String, Map<String, Object>> merged = new java.util.LinkedHashMap<>();
        for (Map<String, Object> asset : first == null ? List.<Map<String, Object>>of() : first) {
            merged.putIfAbsent(text(asset, "dataset_code", "datasetCode"), asset);
        }
        for (Map<String, Object> asset : second == null ? List.<Map<String, Object>>of() : second) {
            merged.putIfAbsent(text(asset, "dataset_code", "datasetCode"), asset);
        }
        merged.remove("");
        return List.copyOf(merged.values());
    }

    private Set<String> lexicalTokens(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}_]+", " ").trim();
        if (normalized.isBlank()) return Set.of();
        Set<String> tokens = new LinkedHashSet<>();
        for (String segment : normalized.split("\\s+")) {
            if (segment.length() >= 2) tokens.add(segment);
            int[] points = segment.codePoints().toArray();
            for (int index = 0; index + 1 < points.length; index++) {
                tokens.add(new String(points, index, 2));
            }
        }
        return Set.copyOf(tokens);
    }

    public Map<String, Object> queryDataset(String dataset, ToolInput input) {
        Map<String, Object> filters = input.getParameter("filters") instanceof Map<?, ?> values
            ? values.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                entry -> String.valueOf(entry.getKey()), Map.Entry::getValue)) : Map.of();
        LocalDate startDate = date(input.getParameterAsString("startDate", ""));
        LocalDate endDate = date(input.getParameterAsString("endDate", ""));
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
