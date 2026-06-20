package com.chatchat.knowledgebase.search;

import java.util.List;

public record SectionNode(
    String sectionId,
    String documentId,
    String title,
    String summary,
    List<String> chunkIds,
    List<Integer> chunkIndexes,
    List<String> keywords,
    String embeddingRef,
    double score
) {
    public SectionNode {
        title = title == null ? "" : title;
        summary = summary == null ? "" : summary;
        chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
        chunkIndexes = chunkIndexes == null ? List.of() : List.copyOf(chunkIndexes);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        embeddingRef = embeddingRef == null ? "" : embeddingRef;
        score = clamp(score);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
