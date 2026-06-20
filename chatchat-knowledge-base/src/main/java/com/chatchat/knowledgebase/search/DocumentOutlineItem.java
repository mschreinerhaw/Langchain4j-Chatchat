package com.chatchat.knowledgebase.search;

import java.util.List;

public record DocumentOutlineItem(
    String docId,
    String section,
    String summary,
    List<String> chunkIds,
    List<Integer> chunkIndexes,
    DocumentOutlineSource source,
    List<String> sectionKeywords,
    String sectionEmbeddingRef
) {
    public DocumentOutlineItem {
        chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
        chunkIndexes = chunkIndexes == null ? List.of() : List.copyOf(chunkIndexes);
        sectionKeywords = sectionKeywords == null ? List.of() : List.copyOf(sectionKeywords);
    }

    public DocumentOutlineItem(String docId,
                               String section,
                               String summary,
                               List<String> chunkIds,
                               List<Integer> chunkIndexes,
                               DocumentOutlineSource source) {
        this(docId, section, summary, chunkIds, chunkIndexes, source, List.of(), null);
    }
}
