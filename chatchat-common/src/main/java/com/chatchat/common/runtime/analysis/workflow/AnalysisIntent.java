package com.chatchat.common.runtime.analysis.workflow;

import java.util.List;
import java.util.Set;

public record AnalysisIntent(
    String intent,
    List<AnalysisEntity> entities,
    Set<AnalysisCapability> requiredCapabilities,
    String freshness,
    boolean evidenceRequired
) {
    public AnalysisIntent {
        intent = intent == null || intent.isBlank() ? "GENERAL" : intent.trim();
        entities = entities == null ? List.of() : List.copyOf(entities);
        requiredCapabilities = requiredCapabilities == null || requiredCapabilities.isEmpty()
            ? Set.of(AnalysisCapability.DOCUMENT_SEARCH) : Set.copyOf(requiredCapabilities);
        freshness = freshness == null || freshness.isBlank() ? "UNSPECIFIED" : freshness.trim();
    }
}
