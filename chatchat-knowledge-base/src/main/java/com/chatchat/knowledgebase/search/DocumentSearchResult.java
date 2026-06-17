package com.chatchat.knowledgebase.search;

import java.util.List;

public record DocumentSearchResult(
    String contractVersion,
    String query,
    String intent,
    int total,
    List<DocumentEvidenceChunk> results,
    String context,
    List<DocumentEvidenceCitation> citations
) {
}
