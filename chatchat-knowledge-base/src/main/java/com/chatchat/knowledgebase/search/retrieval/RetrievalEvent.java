package com.chatchat.knowledgebase.search.retrieval;

public record RetrievalEvent(
    String traceId,
    RetrievalControlStep step,
    RetrievalControlAction action,
    String query,
    int resultSize,
    RetrievalBudgetUsage budgetBefore,
    RetrievalBudgetUsage budgetAfter,
    long latencyMs,
    String reason
) {
}
