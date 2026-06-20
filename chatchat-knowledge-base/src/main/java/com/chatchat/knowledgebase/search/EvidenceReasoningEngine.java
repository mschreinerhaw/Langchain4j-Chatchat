package com.chatchat.knowledgebase.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class EvidenceReasoningEngine {

    private static final String QUERY_NODE_ID = "query";

    public EvidenceReasoningResult reason(DocumentSearchResult result, RetrievalEvidenceQuality quality) {
        if (result == null || result.results().isEmpty()) {
            return EvidenceReasoningResult.empty(result == null ? "" : result.query());
        }
        List<EvidenceNode> evidenceNodes = result.results().stream()
            .map(chunk -> toNode(result.query(), chunk))
            .sorted(Comparator.comparingDouble(EvidenceNode::confidence).reversed())
            .toList();
        EvidenceNode queryNode = new EvidenceNode(
            QUERY_NODE_ID,
            null,
            null,
            null,
            null,
            "Query",
            result.query(),
            EvidenceNodeType.BACKGROUND,
            DocumentEvidenceGrade.A,
            100.0D,
            1.0D,
            List.of()
        );
        List<EvidenceNode> nodes = new ArrayList<>();
        nodes.add(queryNode);
        nodes.addAll(evidenceNodes);

        List<EvidenceEdge> edges = new ArrayList<>();
        for (EvidenceNode node : evidenceNodes) {
            edges.add(new EvidenceEdge(
                QUERY_NODE_ID,
                node.nodeId(),
                edgeType(node.type()),
                node.confidence(),
                edgeReason(node.type())
            ));
        }
        edges.addAll(intraDocumentEdges(evidenceNodes));

        boolean conflictDetected = evidenceNodes.stream().anyMatch(node -> node.type() == EvidenceNodeType.CONTRADICT);
        EvidenceGraph graph = new EvidenceGraph(result.query(), nodes, edges, conflictDetected);
        List<EvidenceReasoningStep> steps = reasoningSteps(evidenceNodes, conflictDetected);
        double confidence = confidence(evidenceNodes, quality, conflictDetected);
        List<String> missingInfo = missingInfo(evidenceNodes, conflictDetected);
        String conclusionMode = conclusionMode(evidenceNodes, conflictDetected);
        return new EvidenceReasoningResult(
            EvidenceReasoningResult.CONTRACT_VERSION,
            result.query(),
            graph,
            steps,
            confidence,
            conflictDetected,
            conclusionMode,
            missingInfo
        );
    }

    private EvidenceNode toNode(String query, DocumentEvidenceChunk chunk) {
        String refId = firstNonBlank(chunk.refId(), refId(chunk));
        EvidenceNodeType type = classifyType(query, chunk);
        DocumentEvidenceGrade grade = grade(chunk.score());
        double confidence = confidence(chunk.score(), grade, type);
        return new EvidenceNode(
            firstNonBlank(refId, chunk.chunkId(), chunk.fileId()),
            chunk.fileId(),
            chunk.chunkId(),
            refId,
            chunk.fileName(),
            chunk.section(),
            excerpt(chunk.content(), 900),
            type,
            grade,
            chunk.score() == null ? 0.0D : chunk.score(),
            confidence,
            List.of(refId)
        );
    }

    private EvidenceNodeType classifyType(String query, DocumentEvidenceChunk chunk) {
        String text = normalize(firstNonBlank(chunk.section(), "") + " " + firstNonBlank(chunk.content(), ""));
        String normalizedQuery = normalize(query);
        if (containsAny(text, "contradict", "conflict", "inconsistent", "冲突", "矛盾", "不一致", "相反")) {
            return EvidenceNodeType.CONTRADICT;
        }
        if (containsAny(text, "example", "case", "sample", "例如", "比如", "案例", "示例")) {
            return EvidenceNodeType.EXAMPLE;
        }
        if (containsAny(text, "definition", "define", "overview", "concept", "定义", "概念", "说明", "是什么")
            || containsAny(normalizedQuery, "是什么", "定义", "概念")) {
            return EvidenceNodeType.DEFINE;
        }
        return EvidenceNodeType.SUPPORT;
    }

    private List<EvidenceEdge> intraDocumentEdges(List<EvidenceNode> nodes) {
        List<EvidenceEdge> edges = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            EvidenceNode left = nodes.get(i);
            for (int j = i + 1; j < nodes.size(); j++) {
                EvidenceNode right = nodes.get(j);
                if (!hasText(left.docId()) || !left.docId().equals(right.docId())) {
                    continue;
                }
                if (hasText(left.section()) && left.section().equals(right.section())) {
                    edges.add(new EvidenceEdge(
                        left.nodeId(),
                        right.nodeId(),
                        EvidenceEdgeType.RELATED,
                        Math.min(left.confidence(), right.confidence()),
                        "same document section"
                    ));
                }
            }
        }
        return edges;
    }

    private List<EvidenceReasoningStep> reasoningSteps(List<EvidenceNode> nodes, boolean conflictDetected) {
        if (nodes.isEmpty()) {
            return List.of();
        }
        List<EvidenceReasoningStep> steps = new ArrayList<>();
        addStep(steps, "definition", "Use definition or overview evidence to establish the answer scope.", nodes, EvidenceNodeType.DEFINE);
        addStep(steps, "support", "Use the strongest supporting evidence as the main basis for the answer.", nodes, EvidenceNodeType.SUPPORT);
        addStep(steps, "example", "Use examples only as illustrative support, not as the primary claim.", nodes, EvidenceNodeType.EXAMPLE);
        if (conflictDetected) {
            addStep(steps, "conflict_review", "Evidence contains conflict signals; answer should explain uncertainty or request review.", nodes, EvidenceNodeType.CONTRADICT);
        }
        if (steps.isEmpty()) {
            List<String> ids = topNodeIds(nodes, 3);
            steps.add(new EvidenceReasoningStep(
                "support",
                "Use available evidence nodes as bounded support for the answer.",
                ids,
                averageConfidence(nodesByIds(nodes, ids))
            ));
        }
        return List.copyOf(steps);
    }

    private void addStep(List<EvidenceReasoningStep> steps,
                         String step,
                         String description,
                         List<EvidenceNode> nodes,
                         EvidenceNodeType type) {
        List<EvidenceNode> matching = nodes.stream()
            .filter(node -> node.type() == type)
            .sorted(Comparator.comparingDouble(EvidenceNode::confidence).reversed())
            .limit(3)
            .toList();
        if (matching.isEmpty()) {
            return;
        }
        steps.add(new EvidenceReasoningStep(
            step,
            description,
            matching.stream().map(EvidenceNode::nodeId).toList(),
            averageConfidence(matching)
        ));
    }

    private double confidence(List<EvidenceNode> nodes, RetrievalEvidenceQuality quality, boolean conflictDetected) {
        double nodeConfidence = averageConfidence(nodes.stream()
            .sorted(Comparator.comparingDouble(EvidenceNode::confidence).reversed())
            .limit(5)
            .toList());
        double qualityConfidence = quality == null ? nodeConfidence : quality.confidence();
        double value = nodeConfidence * 0.7D + qualityConfidence * 0.3D;
        return conflictDetected ? Math.min(value, 0.55D) : value;
    }

    private List<String> missingInfo(List<EvidenceNode> nodes, boolean conflictDetected) {
        List<String> values = new ArrayList<>();
        if (nodes.stream().noneMatch(node -> node.evidenceGrade() == DocumentEvidenceGrade.A)) {
            values.add("No A-grade evidence node available");
        }
        if (conflictDetected) {
            values.add("Conflicting evidence requires review before a definitive answer");
        }
        return List.copyOf(values);
    }

    private String conclusionMode(List<EvidenceNode> nodes, boolean conflictDetected) {
        if (nodes.isEmpty()) {
            return "INSUFFICIENT_EVIDENCE";
        }
        if (conflictDetected) {
            return "REVIEW_REQUIRED";
        }
        boolean hasA = nodes.stream().anyMatch(node -> node.evidenceGrade() == DocumentEvidenceGrade.A);
        return hasA ? "FULL" : "PARTIAL";
    }

    private EvidenceEdgeType edgeType(EvidenceNodeType nodeType) {
        return switch (nodeType) {
            case DEFINE -> EvidenceEdgeType.DEFINES;
            case CONTRADICT -> EvidenceEdgeType.CONTRADICTS;
            case EXAMPLE -> EvidenceEdgeType.EXEMPLIFIES;
            case SUPPORT, BACKGROUND -> EvidenceEdgeType.SUPPORTS;
        };
    }

    private String edgeReason(EvidenceNodeType nodeType) {
        return switch (nodeType) {
            case DEFINE -> "definition evidence";
            case CONTRADICT -> "conflict signal";
            case EXAMPLE -> "example evidence";
            case SUPPORT -> "supporting evidence";
            case BACKGROUND -> "query context";
        };
    }

    private DocumentEvidenceGrade grade(Double score) {
        double value = score == null ? 0.0D : score;
        if (value >= 80.0D) {
            return DocumentEvidenceGrade.A;
        }
        if (value >= 50.0D) {
            return DocumentEvidenceGrade.B;
        }
        return DocumentEvidenceGrade.C;
    }

    private double confidence(Double score, DocumentEvidenceGrade grade, EvidenceNodeType type) {
        double normalizedScore = Math.max(0.0D, Math.min(1.0D, (score == null ? 0.0D : score) / 100.0D));
        double gradeWeight = switch (grade) {
            case A -> 0.95D;
            case B -> 0.72D;
            case C -> 0.45D;
        };
        double typePenalty = type == EvidenceNodeType.CONTRADICT ? 0.15D : 0.0D;
        return Math.max(0.0D, Math.min(1.0D, normalizedScore * 0.55D + gradeWeight * 0.45D - typePenalty));
    }

    private double averageConfidence(List<EvidenceNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return 0.0D;
        }
        return nodes.stream().mapToDouble(EvidenceNode::confidence).average().orElse(0.0D);
    }

    private List<String> topNodeIds(List<EvidenceNode> nodes, int limit) {
        return nodes.stream()
            .sorted(Comparator.comparingDouble(EvidenceNode::confidence).reversed())
            .limit(Math.max(1, limit))
            .map(EvidenceNode::nodeId)
            .toList();
    }

    private List<EvidenceNode> nodesByIds(List<EvidenceNode> nodes, List<String> ids) {
        Set<String> wanted = new LinkedHashSet<>(ids == null ? List.of() : ids);
        return nodes.stream().filter(node -> wanted.contains(node.nodeId())).toList();
    }

    private String refId(DocumentEvidenceChunk chunk) {
        if (chunk == null) {
            return "";
        }
        if (hasText(chunk.fileId()) && chunk.chunkIndex() != null) {
            return "doc://" + chunk.fileId() + "#chunk=" + chunk.chunkIndex();
        }
        return firstNonBlank(chunk.chunkId(), chunk.fileId(), "unknown");
    }

    private String excerpt(String value, int maxChars) {
        if (!hasText(value)) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, Math.max(0, maxChars)).trim();
    }

    private boolean containsAny(String text, String... needles) {
        if (!hasText(text) || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (hasText(needle) && text.contains(normalize(needle))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
