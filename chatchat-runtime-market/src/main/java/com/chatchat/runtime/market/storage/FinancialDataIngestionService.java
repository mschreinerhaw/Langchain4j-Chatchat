package com.chatchat.runtime.market.storage;

import com.chatchat.runtime.market.model.MarketObservation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collector-side boundary that keeps structured persistence independent from news document indexing. */
@Service
public class FinancialDataIngestionService {
    private final FinancialDataStore store;
    private final FinancialAssetCatalogService catalog;

    public FinancialDataIngestionService(FinancialDataStore store, FinancialAssetCatalogService catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    public FinancialDataStore.StoredObservation accept(MarketObservation item) {
        FinancialDatasetDefinition definition = item == null || item.metadata() == null
            ? null : FinancialDatasetDefinition.from(item.metadata());
        if (definition == null) return null;
        List<MarketObservation> observations = expand(item, definition);
        List<FinancialDataStore.StoredObservation> stored = store.storeAll(observations);
        FinancialDataStore.StoredObservation last = stored.isEmpty() ? null : stored.get(stored.size() - 1);
        if (last != null) catalog.index(definition);
        return last;
    }

    private List<MarketObservation> expand(MarketObservation item, FinancialDatasetDefinition definition) {
        String childField = switch (definition.code()) {
            case "market_quote_daily" -> "quotes";
            case "bond_market_overview_monthly" -> "overviewRows";
            case "bond_yield_curve_daily" -> "curvePoints";
            case "bond_counter_quote_daily" -> "counterQuotes";
            case "bond_settlement_daily" -> "settlements";
            case "bond_collateral_monthly" -> "collateralRows";
            default -> marketStatisticsChildren(item, definition);
        };
        if (childField == null || !(item.metadata().get(childField) instanceof Collection<?> children) || children.isEmpty()) {
            return List.of(item);
        }
        List<MarketObservation> expanded = new ArrayList<>();
        int index = 0;
        for (Object child : children) {
            if (!(child instanceof Map<?, ?> childMap)) continue;
            Map<String, Object> metadata = new LinkedHashMap<>(item.metadata());
            metadata.remove(childField);
            childMap.forEach((key, value) -> metadata.put(String.valueOf(key), value));
            String suffix = String.valueOf(metadata.getOrDefault("quoteCode",
                metadata.getOrDefault("bondCode", metadata.getOrDefault("metricCode",
                    metadata.getOrDefault("productCode", metadata.getOrDefault("maturityYears",
                        metadata.getOrDefault("settlementCode", metadata.getOrDefault("section",
                            metadata.getOrDefault("segment", index++)))))))));
            expanded.add(new MarketObservation(item.source(), item.title(), item.content(), item.summary(), item.author(),
                item.sourceUrl() + "#observation=" + suffix, item.publishTime(), item.language(), item.categories(),
                item.tags(), Map.copyOf(metadata)));
        }
        return expanded.isEmpty() ? List.of(item) : List.copyOf(expanded);
    }

    private String marketStatisticsChildren(MarketObservation item, FinancialDatasetDefinition definition) {
        if (!"market_statistics_daily".equals(definition.code())) return null;
        Object sections = item.metadata().get("sections");
        return sections instanceof Collection<?> values && !values.isEmpty() ? "sections" : "segments";
    }
}
