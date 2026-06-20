package com.chatchat.knowledgebase.search;

import java.util.List;

public record KnowledgeNode(
    String nodeId,
    KnowledgeNodeType type,
    String parentDocumentId,
    String title,
    String text,
    String sectionId,
    String chunkId,
    String refId,
    List<String> keywords,
    List<String> citations,
    double score,
    double confidence
) {
    public KnowledgeNode {
        type = type == null ? KnowledgeNodeType.EVIDENCE : type;
        title = title == null ? "" : title;
        text = text == null ? "" : text;
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        citations = citations == null ? List.of() : List.copyOf(citations);
        score = clamp(score);
        confidence = clamp(confidence);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
