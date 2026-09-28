package com.chatchat.knowledgebase.search.retrieval.model;

public record RetrievalValidationResult(
    RetrievalControlAction action,
    String query,
    String reason,
    double confidence
) {
}
