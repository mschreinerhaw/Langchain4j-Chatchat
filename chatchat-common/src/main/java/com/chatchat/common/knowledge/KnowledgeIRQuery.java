package com.chatchat.common.knowledge;

import java.util.List;
import java.util.Set;

/** Scoped semantic query against normalized Knowledge IR. */
public record KnowledgeIRQuery(
    KnowledgeScope scope,
    Set<KnowledgeType> types,
    String query,
    List<String> queryHints,
    int limit
) {
    public KnowledgeIRQuery {
        if (scope == null) throw new IllegalArgumentException("knowledge scope is required");
        types = types == null ? Set.of() : Set.copyOf(types);
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        query = query.trim();
        queryHints = queryHints == null ? List.of() : List.copyOf(queryHints);
        limit = Math.max(1, Math.min(limit <= 0 ? 10 : limit, 100));
    }
}
