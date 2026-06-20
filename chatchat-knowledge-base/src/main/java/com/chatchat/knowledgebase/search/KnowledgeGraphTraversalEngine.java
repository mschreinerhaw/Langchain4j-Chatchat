package com.chatchat.knowledgebase.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class KnowledgeGraphTraversalEngine {

    private static final int DEFAULT_ROOT_SECTIONS = 3;
    private static final int DEFAULT_MAX_SECTION_DEPTH = 1;
    private static final int DEFAULT_MAX_PATHS = 8;

    public KnowledgeTraversalResult traverse(KnowledgeRuntimeGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return KnowledgeTraversalResult.empty(graph == null ? "" : graph.query());
        }
        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        for (KnowledgeNode node : graph.nodes()) {
            nodes.put(node.nodeId(), node);
        }
        List<KnowledgeNodeScore> nodeScores = graph.nodes().stream()
            .map(node -> scoreNode(graph.query(), node))
            .sorted(Comparator.comparingDouble(KnowledgeNodeScore::score).reversed().thenComparing(KnowledgeNodeScore::nodeId))
            .toList();
        Map<String, Double> scoreByNode = new LinkedHashMap<>();
        nodeScores.forEach(score -> scoreByNode.put(score.nodeId(), score.score()));

        List<KnowledgeNode> rootSections = nodeScores.stream()
            .filter(score -> score.nodeType() == KnowledgeNodeType.SECTION)
            .limit(DEFAULT_ROOT_SECTIONS)
            .map(score -> nodes.get(score.nodeId()))
            .filter(node -> node != null)
            .toList();
        Set<String> selectedSectionIds = expandSections(rootSections, graph.edges(), scoreByNode);
        List<KnowledgeTraversalPath> paths = evidencePaths(selectedSectionIds, graph.edges(), nodes, scoreByNode).stream()
            .sorted(Comparator.comparingDouble(KnowledgeTraversalPath::score).reversed().thenComparing(KnowledgeTraversalPath::evidenceNodeId))
            .limit(DEFAULT_MAX_PATHS)
            .toList();
        return new KnowledgeTraversalResult(
            graph.query(),
            nodeScores,
            paths,
            DEFAULT_MAX_SECTION_DEPTH,
            selectedSectionIds.size(),
            (int) paths.stream().map(KnowledgeTraversalPath::evidenceNodeId).distinct().count()
        );
    }

    private KnowledgeNodeScore scoreNode(String query, KnowledgeNode node) {
        double lexical = lexicalOverlap(query, node);
        if (node.type() == KnowledgeNodeType.SECTION) {
            double value = (node.score() * 0.6D) + (node.confidence() * 0.2D) + (lexical * 0.2D);
            return new KnowledgeNodeScore(node.nodeId(), node.type(), value, "section_score + section_confidence + query_overlap");
        }
        double value = (node.confidence() * 0.55D) + (node.score() * 0.25D) + (lexical * 0.2D);
        return new KnowledgeNodeScore(node.nodeId(), node.type(), value, "evidence_confidence + evidence_score + query_overlap");
    }

    private Set<String> expandSections(List<KnowledgeNode> roots,
                                       List<KnowledgeEdge> edges,
                                       Map<String, Double> scoreByNode) {
        Set<String> selected = new LinkedHashSet<>();
        for (KnowledgeNode root : roots) {
            selected.add(root.nodeId());
        }
        for (KnowledgeEdge edge : edges) {
            if (edge.type() != KnowledgeRelationType.SECTION_SECTION || edge.weight() < 0.55D) {
                continue;
            }
            boolean fromSelected = selected.contains(edge.fromNodeId());
            boolean toSelected = selected.contains(edge.toNodeId());
            if (fromSelected && !toSelected && shouldHop(edge, scoreByNode.getOrDefault(edge.toNodeId(), 0.0D))) {
                selected.add(edge.toNodeId());
            } else if (toSelected && !fromSelected && shouldHop(edge, scoreByNode.getOrDefault(edge.fromNodeId(), 0.0D))) {
                selected.add(edge.fromNodeId());
            }
        }
        return selected;
    }

    private boolean shouldHop(KnowledgeEdge edge, double targetNodeScore) {
        if (edge.relation() != null && edge.relation().equals("CONTRADICTS")) {
            return true;
        }
        return edge.weight() >= 0.65D || targetNodeScore >= 0.35D;
    }

    private List<KnowledgeTraversalPath> evidencePaths(Set<String> selectedSectionIds,
                                                       List<KnowledgeEdge> edges,
                                                       Map<String, KnowledgeNode> nodes,
                                                       Map<String, Double> scoreByNode) {
        List<KnowledgeTraversalPath> paths = new ArrayList<>();
        for (KnowledgeEdge edge : edges) {
            if (edge.type() != KnowledgeRelationType.SECTION_EVIDENCE || !selectedSectionIds.contains(edge.fromNodeId())) {
                continue;
            }
            KnowledgeNode section = nodes.get(edge.fromNodeId());
            KnowledgeNode evidence = nodes.get(edge.toNodeId());
            if (section == null || evidence == null) {
                continue;
            }
            double sectionScore = scoreByNode.getOrDefault(section.nodeId(), 0.0D);
            double evidenceScore = scoreByNode.getOrDefault(evidence.nodeId(), 0.0D);
            double edgeBonus = edge.weight() * 0.12D;
            double contradictionPenalty = hasContradictionEdge(evidence.nodeId(), edges) ? 0.18D : 0.0D;
            double pathScore = clamp((sectionScore * 0.42D) + (evidenceScore * 0.46D) + edgeBonus - contradictionPenalty);
            paths.add(new KnowledgeTraversalPath(
                section.nodeId(),
                evidence.nodeId(),
                List.of(section.nodeId(), evidence.nodeId()),
                pathScore,
                "section-first traversal + evidence refinement"
            ));
        }
        return paths;
    }

    private boolean hasContradictionEdge(String evidenceNodeId, List<KnowledgeEdge> edges) {
        return edges.stream()
            .anyMatch(edge -> edge.type() == KnowledgeRelationType.EVIDENCE_EVIDENCE
                && "CONTRADICTS".equals(edge.relation())
                && (evidenceNodeId.equals(edge.fromNodeId()) || evidenceNodeId.equals(edge.toNodeId())));
    }

    private double lexicalOverlap(String query, KnowledgeNode node) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return 0.0D;
        }
        Set<String> nodeTerms = terms(node.title() + " " + node.text() + " " + String.join(" ", node.keywords()));
        if (nodeTerms.isEmpty()) {
            return 0.0D;
        }
        Set<String> intersection = new LinkedHashSet<>(queryTerms);
        intersection.retainAll(nodeTerms);
        return clamp((double) intersection.size() / queryTerms.size());
    }

    private Set<String> terms(String text) {
        String normalized = text == null
            ? ""
            : text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]+", " ")
                .trim();
        if (normalized.isBlank()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                values.add(token);
            }
        }
        return values;
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
