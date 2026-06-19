package com.chatchat.knowledgebase.search;

import java.util.List;

public record DocumentSearchResult(
    String contractVersion,
    String query,
    String intent,
    int total,
    List<DocumentEvidenceChunk> results,
    String context,
    List<DocumentEvidenceCitation> citations,
    RetrievalExecutionState retrievalState,
    RetrievalEvidenceQuality evidenceQuality,
    List<RetrievalEvent> retrievalEvents
) {

    public DocumentSearchResult(String contractVersion,
                                String query,
                                String intent,
                                int total,
                                List<DocumentEvidenceChunk> results,
                                String context,
                                List<DocumentEvidenceCitation> citations) {
        this(
            contractVersion,
            query,
            intent,
            total,
            results,
            context,
            citations,
            null,
            null,
            List.of()
        );
    }
}
