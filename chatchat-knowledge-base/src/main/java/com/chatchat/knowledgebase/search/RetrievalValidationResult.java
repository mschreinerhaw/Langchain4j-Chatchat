package com.chatchat.knowledgebase.search;

public record RetrievalValidationResult(
    RetrievalControlAction action,
    String query,
    String reason,
    double confidence
) {
}
