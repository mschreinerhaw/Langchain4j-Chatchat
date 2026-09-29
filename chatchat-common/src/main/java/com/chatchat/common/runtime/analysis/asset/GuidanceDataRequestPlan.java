package com.chatchat.common.runtime.analysis.asset;

import java.util.List;

/** Declarative metadata requirements, never executable tool arguments or business-data requests. */
public record GuidanceDataRequestPlan(String assetType, String domain, String intent, String query,
                                     String templateId, List<String> requirements, String basis) {
    public GuidanceDataRequestPlan {
        requirements = List.copyOf(requirements);
        if (!List.of("TEMPLATE", "DEFAULT").contains(basis)) throw new IllegalArgumentException("Unknown plan basis");
    }
}
