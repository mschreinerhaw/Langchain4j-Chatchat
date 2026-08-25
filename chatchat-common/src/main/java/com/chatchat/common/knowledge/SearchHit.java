package com.chatchat.common.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Ranked knowledge hit with evidence that explains its admission. */
public record SearchHit<T extends KnowledgeDocument>(
    int rank,
    double score,
    T document,
    Map<String, Object> evidence
) {
    public SearchHit {
        if (rank < 1) throw new IllegalArgumentException("search hit rank must be positive");
        if (document == null) throw new IllegalArgumentException("search hit document is required");
        evidence = evidence == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }
}
