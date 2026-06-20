package com.chatchat.knowledgebase.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class KnowledgeGraphReasoningEngine {

    public KnowledgeReasoningResult reason(KnowledgeRuntimeGraph graph, KnowledgeTraversalResult traversal) {
        if (graph == null || traversal == null || traversal.paths().isEmpty()) {
            return KnowledgeReasoningResult.empty(graph == null ? "" : graph.query());
        }
        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        for (KnowledgeNode node : graph.nodes()) {
            nodes.put(node.nodeId(), node);
        }
        List<NormalizedPath> normalizedPaths = traversal.paths().stream()
            .map(this::normalizePath)
            .sorted(Comparator.comparingDouble(NormalizedPath::score).reversed().thenComparing(path -> path.path().evidenceNodeId()))
            .toList();
        List<KnowledgeEvidenceCluster> clusters = clusters(normalizedPaths);
        List<KnowledgeContradiction> contradictions = contradictions(graph.edges());
        double confidence = confidence(clusters, contradictions);
        String conclusionMode = conclusionMode(clusters, contradictions, confidence);
        List<KnowledgeReasoningStep> steps = steps(nodes, clusters, contradictions, confidence);
        List<String> missingInfo = missingInfo(clusters, contradictions);
        return new KnowledgeReasoningResult(
            graph.query(),
            clusters,
            contradictions,
            steps,
            confidence,
            conclusionMode,
            missingInfo
        );
    }

    private NormalizedPath normalizePath(KnowledgeTraversalPath path) {
        int length = path.nodeIds().isEmpty() ? 1 : path.nodeIds().size();
        double pathLengthPenalty = 1.0D + Math.max(0, length - 2) * 0.08D;
        double normalized = clamp(path.score() / pathLengthPenalty);
        return new NormalizedPath(path, normalized);
    }

    private List<KnowledgeEvidenceCluster> clusters(List<NormalizedPath> paths) {
        Map<String, List<NormalizedPath>> bySection = new LinkedHashMap<>();
        for (NormalizedPath path : paths) {
            bySection.computeIfAbsent(path.path().rootSectionId(), ignored -> new ArrayList<>()).add(path);
        }
        List<KnowledgeEvidenceCluster> clusters = new ArrayList<>();
        for (Map.Entry<String, List<NormalizedPath>> entry : bySection.entrySet()) {
            List<NormalizedPath> sectionPaths = entry.getValue().stream()
                .sorted(Comparator.comparingDouble(NormalizedPath::score).reversed())
                .limit(4)
                .toList();
            Set<String> evidenceIds = new LinkedHashSet<>();
            sectionPaths.forEach(path -> evidenceIds.add(path.path().evidenceNodeId()));
            double aggregate = sectionPaths.stream().mapToDouble(NormalizedPath::score).average().orElse(0.0D);
            clusters.add(new KnowledgeEvidenceCluster(
                entry.getKey(),
                List.copyOf(evidenceIds),
                aggregate,
                "multi-path evidence aggregation by root section"
            ));
        }
        return clusters.stream()
            .sorted(Comparator.comparingDouble(KnowledgeEvidenceCluster::aggregateScore).reversed().thenComparing(KnowledgeEvidenceCluster::sectionNodeId))
            .toList();
    }

    private List<KnowledgeContradiction> contradictions(List<KnowledgeEdge> edges) {
        return edges.stream()
            .filter(edge -> "CONTRADICTS".equals(edge.relation()))
            .map(edge -> new KnowledgeContradiction(
                edge.fromNodeId(),
                edge.toNodeId(),
                edge.relation(),
                edge.weight(),
                edge.reason()
            ))
            .sorted(Comparator.comparingDouble(KnowledgeContradiction::confidence).reversed())
            .toList();
    }

    private List<KnowledgeReasoningStep> steps(Map<String, KnowledgeNode> nodes,
                                               List<KnowledgeEvidenceCluster> clusters,
                                               List<KnowledgeContradiction> contradictions,
                                               double confidence) {
        if (clusters.isEmpty()) {
            return List.of();
        }
        List<KnowledgeReasoningStep> steps = new ArrayList<>();
        List<String> sectionIds = clusters.stream()
            .map(KnowledgeEvidenceCluster::sectionNodeId)
            .limit(3)
            .toList();
        steps.add(new KnowledgeReasoningStep(
            "scope",
            "Select the strongest section clusters as the reasoning scope.",
            sectionIds,
            averageClusterScore(clusters)
        ));
        List<String> evidenceIds = clusters.stream()
            .flatMap(cluster -> cluster.evidenceNodeIds().stream())
            .distinct()
            .limit(5)
            .toList();
        steps.add(new KnowledgeReasoningStep(
            "support",
            "Aggregate evidence from multiple graph paths instead of relying on one path.",
            evidenceIds,
            averageClusterScore(clusters)
        ));
        if (!contradictions.isEmpty()) {
            steps.add(new KnowledgeReasoningStep(
                "conflict_review",
                "Contradictory graph edges require review or a cautious answer.",
                contradictions.stream()
                    .flatMap(item -> List.of(item.leftNodeId(), item.rightNodeId()).stream())
                    .distinct()
                    .limit(6)
                    .toList(),
                Math.min(0.55D, confidence)
            ));
        }
        steps.add(new KnowledgeReasoningStep(
            "conclusion",
            "Use the aggregated clusters and conflict scan to decide answer completeness.",
            topExistingNodes(nodes, sectionIds, evidenceIds),
            confidence
        ));
        return steps;
    }

    private List<String> topExistingNodes(Map<String, KnowledgeNode> nodes, List<String> sectionIds, List<String> evidenceIds) {
        List<String> values = new ArrayList<>();
        for (String sectionId : sectionIds) {
            if (nodes.containsKey(sectionId)) {
                values.add(sectionId);
            }
        }
        for (String evidenceId : evidenceIds) {
            if (nodes.containsKey(evidenceId)) {
                values.add(evidenceId);
            }
        }
        return values.stream().distinct().limit(6).toList();
    }

    private double confidence(List<KnowledgeEvidenceCluster> clusters, List<KnowledgeContradiction> contradictions) {
        double clusterConfidence = averageClusterScore(clusters);
        if (!contradictions.isEmpty()) {
            double contradictionPenalty = contradictions.stream()
                .mapToDouble(KnowledgeContradiction::confidence)
                .max()
                .orElse(0.0D) * 0.35D;
            return clamp(Math.min(clusterConfidence, 0.68D) - contradictionPenalty);
        }
        return clusterConfidence;
    }

    private double averageClusterScore(List<KnowledgeEvidenceCluster> clusters) {
        return clusters.stream()
            .sorted(Comparator.comparingDouble(KnowledgeEvidenceCluster::aggregateScore).reversed())
            .limit(3)
            .mapToDouble(KnowledgeEvidenceCluster::aggregateScore)
            .average()
            .orElse(0.0D);
    }

    private String conclusionMode(List<KnowledgeEvidenceCluster> clusters,
                                  List<KnowledgeContradiction> contradictions,
                                  double confidence) {
        if (clusters.isEmpty()) {
            return "INSUFFICIENT_EVIDENCE";
        }
        if (!contradictions.isEmpty()) {
            return "REVIEW_REQUIRED";
        }
        return confidence >= 0.72D ? "FULL" : "PARTIAL";
    }

    private List<String> missingInfo(List<KnowledgeEvidenceCluster> clusters, List<KnowledgeContradiction> contradictions) {
        List<String> values = new ArrayList<>();
        if (clusters.isEmpty()) {
            values.add("No graph traversal paths could be aggregated");
        }
        if (!contradictions.isEmpty()) {
            values.add("Contradictory graph evidence requires review before a definitive answer");
        }
        return List.copyOf(values);
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private record NormalizedPath(KnowledgeTraversalPath path, double score) {
    }
}
