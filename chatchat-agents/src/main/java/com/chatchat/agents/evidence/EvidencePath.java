package com.chatchat.agents.evidence;

import java.util.List;

public record EvidencePath(
    List<String> nodeIds,
    double score,
    List<String> sqlLineage,
    boolean hasTrustedSql,
    boolean executable
) {

    public EvidencePath(List<String> nodeIds, double score, List<String> sqlLineage) {
        this(nodeIds, score, sqlLineage, sqlLineage != null && !sqlLineage.isEmpty(), true);
    }

    public EvidencePath {
        nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
        sqlLineage = sqlLineage == null ? List.of() : List.copyOf(sqlLineage);
    }
}
