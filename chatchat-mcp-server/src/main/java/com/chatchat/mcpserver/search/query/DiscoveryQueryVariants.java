package com.chatchat.mcpserver.search.query;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Compatibility facade for callers that only need query text. New discovery code
 * should use {@link DiscoveryQueryPlan} so source and per-unit evidence are retained.
 */
public final class DiscoveryQueryVariants {

    private DiscoveryQueryVariants() {
    }

    public static List<String> from(Map<String, Object> filters) {
        return DiscoveryQueryPlan.from(filters).queries();
    }

    public static List<String> from(Map<String, Object> filters, Collection<String> generatedSignals) {
        return DiscoveryQueryPlan.from(filters, generatedSignals).queries();
    }
}
