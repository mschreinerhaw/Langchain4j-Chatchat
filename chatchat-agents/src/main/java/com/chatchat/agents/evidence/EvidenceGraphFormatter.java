package com.chatchat.agents.evidence;

import java.util.List;
import java.util.Map;

public class EvidenceGraphFormatter {

    private static final int CONTENT_PREVIEW_LIMIT = 260;

    public String format(EvidenceGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Evidence graph execution (contractVersion=")
            .append(graph.contractVersion())
            .append("):\n");
        builder.append("queryId: ").append(graph.queryId()).append('\n');
        builder.append("nodeCount: ").append(graph.nodes().size()).append('\n');
        builder.append("edgeCount: ").append(graph.edges().size()).append('\n');
        builder.append("pathCount: ").append(graph.validPaths().size()).append('\n');
        if (!graph.sqlLineage().isEmpty()) {
            builder.append("sqlLineage: ").append(String.join(", ", graph.sqlLineage())).append('\n');
        }
        appendNodes(builder, graph.nodes());
        appendEdges(builder, graph.edges());
        appendPaths(builder, graph.validPaths());
        return builder.toString().trim();
    }

    private void appendNodes(StringBuilder builder, Map<String, EvidenceGraphNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        builder.append("\nNodes:\n");
        for (EvidenceGraphNode node : nodes.values()) {
            builder.append("[Node ").append(node.id()).append("]\n");
            builder.append("type: ").append(node.type()).append('\n');
            appendLine(builder, "sourceRef", node.sourceRef());
            builder.append("confidence: ").append(node.confidence()).append('\n');
            builder.append("validated: ").append(node.validated()).append('\n');
            appendMetadata(builder, node.metadata());
            appendLine(builder, "contentPreview", preview(node.normalizedContent()));
        }
    }

    private void appendEdges(StringBuilder builder, List<EvidenceGraphEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return;
        }
        builder.append("\nEdges:\n");
        for (EvidenceGraphEdge edge : edges) {
            builder.append(edge.fromNodeId())
                .append(" -> ")
                .append(edge.toNodeId())
                .append(" type=")
                .append(edge.type())
                .append(" weight=")
                .append(edge.weight());
            if (edge.reasoning() != null && !edge.reasoning().isBlank()) {
                builder.append(" reasoning=\"").append(edge.reasoning()).append('"');
            }
            builder.append('\n');
        }
    }

    private void appendPaths(StringBuilder builder, List<EvidencePath> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        builder.append("\nValid evidence paths:\n");
        for (int i = 0; i < paths.size(); i++) {
            EvidencePath path = paths.get(i);
            builder.append("[Path ").append(i + 1).append("]\n");
            builder.append("nodes: ").append(String.join(" -> ", path.nodeIds())).append('\n');
            builder.append("score: ").append(path.score()).append('\n');
            if (!path.sqlLineage().isEmpty()) {
                builder.append("sqlLineage: ").append(String.join(", ", path.sqlLineage())).append('\n');
            }
        }
    }

    private void appendMetadata(StringBuilder builder, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        appendLine(builder, "sqlType", stringValue(metadata.get("sqlType")));
        appendLine(builder, "isComplete", stringValue(metadata.get("isComplete")));
        appendLine(builder, "sqlStates", listValue(metadata.get("sqlStates")));
        appendLine(builder, "executionVerified", stringValue(metadata.get("executionVerified")));
        appendLine(builder, "validationScore", stringValue(metadata.get("validationScore")));
        appendLine(builder, "tables", listValue(metadata.get("tables")));
        appendLine(builder, "columns", listValue(metadata.get("columns")));
        appendLine(builder, "evidenceGrade", stringValue(metadata.get("evidenceGrade")));
        appendLine(builder, "fileId", stringValue(metadata.get("fileId")));
        appendLine(builder, "section", stringValue(metadata.get("section")));
        appendLine(builder, "url", stringValue(metadata.get("url")));
    }

    private String listValue(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return String.join(", ", list.stream().map(String::valueOf).toList());
        }
        return null;
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= CONTENT_PREVIEW_LIMIT
            ? normalized
            : normalized.substring(0, CONTENT_PREVIEW_LIMIT);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(label).append(": ").append(value).append('\n');
        }
    }
}
