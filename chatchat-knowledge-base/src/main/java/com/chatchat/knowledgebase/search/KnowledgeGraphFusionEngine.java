package com.chatchat.knowledgebase.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class KnowledgeGraphFusionEngine {

    private static final String EVIDENCE_PREFIX = "evidence://";

    public KnowledgeRuntimeGraph fuse(SectionGraph sectionGraph, EvidenceGraph evidenceGraph) {
        String documentId = firstNonBlank(
            sectionGraph == null ? "" : sectionGraph.documentId(),
            firstEvidenceDocumentId(evidenceGraph)
        );
        String query = firstNonBlank(
            sectionGraph == null ? "" : sectionGraph.query(),
            evidenceGraph == null ? "" : evidenceGraph.query()
        );
        if ((sectionGraph == null || sectionGraph.nodes().isEmpty())
            && (evidenceGraph == null || evidenceGraph.nodes().isEmpty())) {
            return KnowledgeRuntimeGraph.empty(documentId, query);
        }
        Map<String, KnowledgeNode> nodes = new LinkedHashMap<>();
        List<KnowledgeEdge> edges = new ArrayList<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        Map<String, SectionNode> sectionsById = new LinkedHashMap<>();

        if (sectionGraph != null) {
            for (SectionNode section : sectionGraph.nodes()) {
                sectionsById.put(section.sectionId(), section);
                nodes.put(section.sectionId(), toKnowledgeNode(section));
            }
            for (SectionEdge edge : sectionGraph.edges()) {
                addEdge(edges, edgeKeys, new KnowledgeEdge(
                    edge.fromSectionId(),
                    edge.toSectionId(),
                    KnowledgeRelationType.SECTION_SECTION,
                    edge.type().name(),
                    edge.weight(),
                    edge.reason()
                ));
            }
        }

        if (evidenceGraph != null) {
            for (EvidenceNode evidence : evidenceGraph.nodes()) {
                String nodeId = evidenceNodeId(evidence.nodeId());
                nodes.put(nodeId, toKnowledgeNode(nodeId, evidence));
                attachEvidenceToSections(edges, edgeKeys, sectionsById, evidence, nodeId);
            }
            for (EvidenceEdge edge : evidenceGraph.edges()) {
                addEdge(edges, edgeKeys, new KnowledgeEdge(
                    evidenceNodeId(edge.sourceNodeId()),
                    evidenceNodeId(edge.targetNodeId()),
                    KnowledgeRelationType.EVIDENCE_EVIDENCE,
                    edge.type().name(),
                    edge.confidence(),
                    edge.reason()
                ));
            }
        }

        return new KnowledgeRuntimeGraph(
            documentId,
            query,
            nodes.values().stream()
                .sorted(Comparator.comparing(KnowledgeNode::type).thenComparing(KnowledgeNode::nodeId))
                .toList(),
            List.copyOf(edges)
        );
    }

    private KnowledgeNode toKnowledgeNode(SectionNode section) {
        return new KnowledgeNode(
            section.sectionId(),
            KnowledgeNodeType.SECTION,
            section.documentId(),
            section.title(),
            section.summary(),
            section.sectionId(),
            null,
            null,
            section.keywords(),
            List.of(),
            section.score(),
            section.score()
        );
    }

    private KnowledgeNode toKnowledgeNode(String nodeId, EvidenceNode evidence) {
        return new KnowledgeNode(
            nodeId,
            KnowledgeNodeType.EVIDENCE,
            evidence.docId(),
            firstNonBlank(evidence.section(), evidence.fileName(), evidence.nodeId()),
            evidence.text(),
            null,
            evidence.chunkId(),
            evidence.refId(),
            List.of(),
            evidence.citations(),
            evidence.score() / 100.0D,
            evidence.confidence()
        );
    }

    private void attachEvidenceToSections(List<KnowledgeEdge> edges,
                                          Set<String> edgeKeys,
                                          Map<String, SectionNode> sectionsById,
                                          EvidenceNode evidence,
                                          String evidenceNodeId) {
        if (sectionsById.isEmpty() || !hasText(evidence.docId())) {
            return;
        }
        List<SectionNode> matching = sectionsById.values().stream()
            .filter(section -> evidence.docId().equals(section.documentId()))
            .filter(section -> sameSection(section, evidence))
            .toList();
        if (matching.isEmpty()) {
            matching = sectionsById.values().stream()
                .filter(section -> evidence.docId().equals(section.documentId()))
                .filter(section -> section.chunkIds().contains(evidence.chunkId()))
                .toList();
        }
        for (SectionNode section : matching) {
            addEdge(edges, edgeKeys, new KnowledgeEdge(
                section.sectionId(),
                evidenceNodeId,
                KnowledgeRelationType.SECTION_EVIDENCE,
                "CONTAINS_EVIDENCE",
                Math.max(0.35D, evidence.confidence()),
                "evidence belongs to matched section"
            ));
        }
    }

    private boolean sameSection(SectionNode section, EvidenceNode evidence) {
        String left = normalize(section.title());
        String right = normalize(evidence.section());
        return hasText(left) && hasText(right) && (left.contains(right) || right.contains(left));
    }

    private void addEdge(List<KnowledgeEdge> edges, Set<String> edgeKeys, KnowledgeEdge edge) {
        if (edge == null || !hasText(edge.fromNodeId()) || !hasText(edge.toNodeId()) || edge.fromNodeId().equals(edge.toNodeId())) {
            return;
        }
        String key = edge.fromNodeId() + "->" + edge.toNodeId() + ":" + edge.type() + ":" + edge.relation();
        if (edgeKeys.add(key)) {
            edges.add(edge);
        }
    }

    private String evidenceNodeId(String nodeId) {
        String value = firstNonBlank(nodeId, "unknown");
        return value.startsWith(EVIDENCE_PREFIX) ? value : EVIDENCE_PREFIX + value;
    }

    private String firstEvidenceDocumentId(EvidenceGraph graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return "";
        }
        return graph.nodes().stream()
            .map(EvidenceNode::docId)
            .filter(this::hasText)
            .findFirst()
            .orElse("");
    }

    private String normalize(String value) {
        return value == null
            ? ""
            : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]+", "");
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
