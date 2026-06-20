package com.chatchat.knowledgebase.search;

import java.util.List;

public record EvidenceNode(
    String nodeId,
    String docId,
    String chunkId,
    String refId,
    String fileName,
    String section,
    String text,
    EvidenceNodeType type,
    DocumentEvidenceGrade evidenceGrade,
    double score,
    double confidence,
    List<String> citations
) {
    public EvidenceNode {
        type = type == null ? EvidenceNodeType.SUPPORT : type;
        evidenceGrade = evidenceGrade == null ? DocumentEvidenceGrade.C : evidenceGrade;
        confidence = clamp(confidence);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
