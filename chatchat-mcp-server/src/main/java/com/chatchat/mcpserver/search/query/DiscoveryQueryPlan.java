package com.chatchat.mcpserver.search.query;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical retrieval plan shared by asset and template discovery.
 *
 * <p>Every model-produced keyword or phrase is an independent query unit. Search
 * implementations execute the units separately, then union and deduplicate the
 * candidates while retaining the unit that produced each hit. They must never
 * concatenate all units into one lossy query string.</p>
 */
public final class DiscoveryQueryPlan {

    public static final String SCHEMA_VERSION = "discovery_query_plan.v1";
    public static final int MAX_QUERY_UNITS = 96;
    private static final List<String> FILTER_KEYS = List.of(
        "assetName", "asset_name", "toolName", "name", "template", "templateId", "template_id",
        "businessGroup", "business_group", "group", "groupName", "group_name", "labels",
        "groupDescription", "group_description", "service", "target", "category",
        "intent", "goal", "query", "q", "bilingualQuery", "bilingualSearch", "intentZh", "intentEn",
        "queryTerms", "searchTerms", "bilingualIntent", "intentAliases", "keywords",
        "retrievalSignals", "intentCandidates", "intent_candidates"
    );
    private static final List<String> NESTED_KEYS = List.of(
        "intent", "query", "queries", "term", "text", "label", "name", "goal",
        "queryTerms", "searchTerms", "bilingualIntent", "intentAliases", "aliases",
        "keywords", "retrievalSignals"
    );

    private final List<QueryUnit> units;

    private DiscoveryQueryPlan(List<QueryUnit> units) {
        this.units = List.copyOf(units);
    }

    public static DiscoveryQueryPlan from(Map<String, Object> filters) {
        return from(filters, List.of());
    }

    public static DiscoveryQueryPlan from(Map<String, Object> filters,
                                          Collection<String> generatedSignals) {
        LinkedHashMap<String, QueryUnitDraft> drafts = new LinkedHashMap<>();
        if (filters != null) {
            for (String key : FILTER_KEYS) {
                add(drafts, filters.get(key), key);
            }
        }
        if (generatedSignals != null) {
            int index = 0;
            for (String signal : generatedSignals) {
                add(drafts, signal, "generatedSignals[" + index++ + "]");
            }
        }
        List<QueryUnit> units = new ArrayList<>();
        int ordinal = 1;
        for (QueryUnitDraft draft : drafts.values()) {
            units.add(new QueryUnit("q" + ordinal, ordinal, draft.query(), draft.sourcePaths()));
            ordinal++;
        }
        return new DiscoveryQueryPlan(units);
    }

    public List<QueryUnit> units() {
        return units;
    }

    public List<String> queries() {
        return units.stream().map(QueryUnit::query).toList();
    }

    public Map<String, Object> metadata() {
        return Map.of(
            "schemaVersion", SCHEMA_VERSION,
            "mode", "independent_query_units",
            "queryUnitCount", units.size(),
            "queryUnits", units.stream().map(QueryUnit::metadata).toList(),
            "executionPolicy", "search_each_unit_independently",
            "aggregationPolicy", "union_then_deduplicate_preserving_per_unit_evidence"
        );
    }

    private static void add(LinkedHashMap<String, QueryUnitDraft> drafts,
                            Object value,
                            String sourcePath) {
        if (value == null || drafts.size() >= MAX_QUERY_UNITS) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : NESTED_KEYS) {
                if (map.containsKey(key)) {
                    add(drafts, map.get(key), sourcePath + "." + key);
                }
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                add(drafts, item, sourcePath + "[" + index++ + "]");
            }
            return;
        }
        if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                add(drafts, Array.get(value, index), sourcePath + "[" + index + "]");
            }
            return;
        }
        String query = SearchQueryTokenizer.normalize(String.valueOf(value));
        if (query.isBlank()) {
            return;
        }
        QueryUnitDraft existing = drafts.get(query);
        if (existing == null) {
            drafts.put(query, new QueryUnitDraft(query, new ArrayList<>(List.of(sourcePath))));
        } else if (!existing.sourcePaths().contains(sourcePath)) {
            existing.sourcePaths().add(sourcePath);
        }
    }

    public record QueryUnit(String id, int ordinal, String query, List<String> sourcePaths) {
        public QueryUnit {
            sourcePaths = List.copyOf(sourcePaths);
        }

        Map<String, Object> metadata() {
            return Map.of(
                "id", id,
                "ordinal", ordinal,
                "query", query,
                "sourcePaths", sourcePaths
            );
        }
    }

    private record QueryUnitDraft(String query, List<String> sourcePaths) {
    }
}
