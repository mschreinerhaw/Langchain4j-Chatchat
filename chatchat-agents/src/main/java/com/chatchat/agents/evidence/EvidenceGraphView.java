package com.chatchat.agents.evidence;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EvidenceGraphView {

    public EvidenceGraph project(EvidenceGraph graph, DocumentSelectionContext selectionContext) {
        if (graph == null || graph.nodes().isEmpty()) {
            return graph == null
                ? new EvidenceGraph(EvidenceGraph.CONTRACT_VERSION, "query:current", Map.of(), List.of(), List.of(), List.of())
                : graph;
        }
        DocumentSelectionContext context = selectionContext == null ? DocumentSelectionContext.unrestricted() : selectionContext;
        if (!context.active()) {
            return graph;
        }
        Map<String, EvidenceGraphNode> visibleNodes = new LinkedHashMap<>();
        graph.nodes().forEach((id, node) -> {
            if (context.allowsNode(node)) {
                visibleNodes.put(id, node);
            }
        });
        List<EvidenceGraphEdge> visibleEdges = graph.edges().stream()
            .filter(edge -> edge != null
                && visibleNodes.containsKey(edge.fromNodeId())
                && visibleNodes.containsKey(edge.toNodeId()))
            .toList();
        List<EvidencePath> visiblePaths = graph.validPaths().stream()
            .filter(path -> path != null && visibleNodes.keySet().containsAll(path.nodeIds()))
            .toList();
        Set<String> sqlLineage = new LinkedHashSet<>();
        for (EvidencePath path : visiblePaths) {
            sqlLineage.addAll(path.sqlLineage());
        }
        return new EvidenceGraph(
            graph.contractVersion(),
            graph.queryId(),
            visibleNodes,
            visibleEdges,
            visiblePaths,
            List.copyOf(sqlLineage)
        );
    }
}
