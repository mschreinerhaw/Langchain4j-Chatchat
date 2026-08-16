package com.chatchat.mcpserver.search;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Extracts independent discovery queries without collapsing multi-intent input into one noisy query. */
public final class DiscoveryQueryVariants {

    private static final int MAX_VARIANTS = 96;
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

    private DiscoveryQueryVariants() {
    }

    public static List<String> from(Map<String, Object> filters) {
        return from(filters, List.of());
    }

    public static List<String> from(Map<String, Object> filters, Collection<String> generatedSignals) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        if (filters != null) {
            for (String key : FILTER_KEYS) {
                add(variants, filters.get(key), false);
            }
        }
        if (generatedSignals != null) {
            generatedSignals.forEach(signal -> add(variants, signal, false));
        }
        return variants.stream().limit(MAX_VARIANTS).toList();
    }

    private static void add(LinkedHashSet<String> variants, Object value, boolean nestedMap) {
        if (value == null || variants.size() >= MAX_VARIANTS) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : NESTED_KEYS) {
                if (map.containsKey(key)) {
                    add(variants, map.get(key), true);
                }
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                add(variants, item, nestedMap);
            }
            return;
        }
        if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                add(variants, Array.get(value, index), nestedMap);
            }
            return;
        }
        String normalized = SearchQueryTokenizer.normalize(String.valueOf(value));
        if (!normalized.isBlank()) {
            variants.add(normalized);
        }
    }
}
