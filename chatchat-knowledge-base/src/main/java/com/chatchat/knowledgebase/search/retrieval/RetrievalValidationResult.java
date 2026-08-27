package com.chatchat.knowledgebase.search.retrieval;

public record RetrievalValidationResult(
    RetrievalControlAction action,
    String query,
    String reason,
    double confidence
) {
}
