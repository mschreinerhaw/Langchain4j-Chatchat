package com.chatchat.agents.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EvidenceGraph(
    String contractVersion,
    String queryId,
    Map<String, EvidenceGraphNode> nodes,
    List<EvidenceGraphEdge> edges,
    List<EvidencePath> validPaths,
    List<String> sqlLineage
) {

    public static final String CONTRACT_VERSION = "evidence_graph_v1";

    public EvidenceGraph {
        contractVersion = contractVersion == null || contractVersion.isBlank() ? CONTRACT_VERSION : contractVersion;
        queryId = queryId == null || queryId.isBlank() ? "query:current" : queryId;
        nodes = nodes == null ? Map.of() : new LinkedHashMap<>(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        validPaths = validPaths == null ? List.of() : List.copyOf(validPaths);
        sqlLineage = sqlLineage == null ? List.of() : List.copyOf(sqlLineage);
    }
}
