package com.chatchat.common.runtime.summary.analysis.governance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, queryable graph for evidence, reports, claims and management decisions. */
public final class DataAnalysisLineageGraph {

    public static final String SCHEMA_VERSION = "analysis_lineage_graph.v1";

    public record Node(String nodeId, String nodeType, Map<String, Object> attributes) {
        public Node {
            nodeId = nodeId == null ? "" : nodeId.trim();
            nodeType = nodeType == null ? "UNKNOWN" : nodeType.trim();
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
        public Map<String, Object> toMap() {
            return Map.of("nodeId", nodeId, "nodeType", nodeType, "attributes", attributes);
        }
    }

    private final Map<String, Node> nodes;
    private final List<DataAnalysisLayerGovernanceContract.LineageEdge> edges;

    public DataAnalysisLineageGraph(List<Node> nodes,
                                    List<DataAnalysisLayerGovernanceContract.LineageEdge> edges) {
        Map<String, Node> indexed = new LinkedHashMap<>();
        if (nodes != null) nodes.stream().filter(node -> node != null && !node.nodeId().isBlank())
            .forEach(node -> indexed.put(node.nodeId(), node));
        this.nodes = Map.copyOf(indexed);
        this.edges = edges == null ? List.of() : edges.stream()
            .filter(edge -> edge != null && indexed.containsKey(edge.fromId())
                && indexed.containsKey(edge.toId())).distinct().toList();
    }

    public List<Node> ancestors(String nodeId) { return traverse(nodeId, true); }
    public List<Node> descendants(String nodeId) { return traverse(nodeId, false); }
    public List<Node> nodes() { return List.copyOf(nodes.values()); }
    public List<DataAnalysisLayerGovernanceContract.LineageEdge> edges() { return edges; }

    public DataAnalysisLineageGraph plus(List<Node> additionalNodes,
                                         List<DataAnalysisLayerGovernanceContract.LineageEdge> additionalEdges) {
        Map<String, Node> mergedNodeIndex = new LinkedHashMap<>(nodes);
        if (additionalNodes != null) additionalNodes.forEach(node -> {
            if (node == null || node.nodeId().isBlank()) return;
            Node previous = mergedNodeIndex.get(node.nodeId());
            if (previous == null) {
                mergedNodeIndex.put(node.nodeId(), node);
                return;
            }
            Map<String, Object> attributes = new LinkedHashMap<>(previous.attributes());
            attributes.putAll(node.attributes());
            mergedNodeIndex.put(node.nodeId(), new Node(node.nodeId(),
                "UNKNOWN".equals(node.nodeType()) ? previous.nodeType() : node.nodeType(), attributes));
        });
        List<DataAnalysisLayerGovernanceContract.LineageEdge> mergedEdges = new ArrayList<>(edges);
        if (additionalEdges != null) mergedEdges.addAll(additionalEdges);
        return new DataAnalysisLineageGraph(List.copyOf(mergedNodeIndex.values()), mergedEdges);
    }

    public List<Node> supportingEvidence(String nodeId) {
        Set<String> ancestors = ancestors(nodeId).stream().map(Node::nodeId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return ancestors.stream().map(nodes::get).filter(node -> node != null
            && "EVIDENCE".equals(node.nodeType())).toList();
    }

    public Map<String, Object> toMap() {
        return Map.of("schemaVersion", SCHEMA_VERSION,
            "nodes", nodes.values().stream().map(Node::toMap).toList(),
            "edges", edges.stream().map(DataAnalysisLayerGovernanceContract.LineageEdge::toMap).toList(),
            "nodeCount", nodes.size(), "edgeCount", edges.size());
    }

    /** Restores a graph persisted in runtime metadata so callers can query it across rounds. */
    public static DataAnalysisLineageGraph fromMap(Object value) {
        if (!(value instanceof Map<?, ?> graph)) return new DataAnalysisLineageGraph(List.of(), List.of());
        List<Node> restoredNodes = new ArrayList<>();
        Object nodeValues = graph.get("nodes");
        if (nodeValues instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (!(item instanceof Map<?, ?> node)) continue;
                restoredNodes.add(new Node(text(node.get("nodeId")), text(node.get("nodeType")),
                    stringMap(node.get("attributes"))));
            }
        }
        List<DataAnalysisLayerGovernanceContract.LineageEdge> restoredEdges = new ArrayList<>();
        Object edgeValues = graph.get("edges");
        if (edgeValues instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (!(item instanceof Map<?, ?> edge)) continue;
                restoredEdges.add(new DataAnalysisLayerGovernanceContract.LineageEdge(
                    text(edge.get("edgeId")), text(edge.get("fromId")), text(edge.get("toId")),
                    enumValue(DataAnalysisLayerGovernanceContract.Relation.class,
                        edge.get("relation"), DataAnalysisLayerGovernanceContract.Relation.DERIVED_FROM),
                    enumValue(DataAnalysisLayerGovernanceContract.Layer.class,
                        edge.get("producerLayer"), DataAnalysisLayerGovernanceContract.Layer.EVIDENCE)));
            }
        }
        return new DataAnalysisLineageGraph(restoredNodes, restoredEdges);
    }

    private List<Node> traverse(String start, boolean reverse) {
        if (start == null || !nodes.containsKey(start)) return List.of();
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (DataAnalysisLayerGovernanceContract.LineageEdge edge : edges) {
                String next = reverse && edge.toId().equals(current) ? edge.fromId()
                    : !reverse && edge.fromId().equals(current) ? edge.toId() : null;
                if (next != null && !next.equals(start) && visited.add(next)) queue.addLast(next);
            }
        }
        List<Node> result = new ArrayList<>();
        visited.forEach(id -> result.add(nodes.get(id)));
        return List.copyOf(result);
    }

    private static Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, E fallback) {
        try {
            return value == null ? fallback : Enum.valueOf(type, String.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
