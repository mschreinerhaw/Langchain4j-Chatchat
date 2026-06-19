package com.chatchat.knowledgebase.search;

public record RetrievalExecutionState(
    String traceId,
    int attempt,
    RetrievalBudgetUsage budgetUsed,
    RetrievalControlAction lastAction,
    int emptyResultCount,
    RetrievalEvidenceQuality quality
) {

    public static RetrievalExecutionState started(String traceId) {
        return new RetrievalExecutionState(
            traceId,
            0,
            RetrievalBudgetUsage.zero(),
            RetrievalControlAction.ALLOW,
            0,
            null
        );
    }

    public RetrievalExecutionState withAction(RetrievalControlAction action) {
        return new RetrievalExecutionState(traceId, attempt, budgetUsed, action, emptyResultCount, quality);
    }

    public RetrievalExecutionState withBudgetUsed(RetrievalBudgetUsage used) {
        return new RetrievalExecutionState(traceId, attempt, used, lastAction, emptyResultCount, quality);
    }

    public RetrievalExecutionState withEmptyResult() {
        return new RetrievalExecutionState(traceId, attempt, budgetUsed, lastAction, emptyResultCount + 1, quality);
    }

    public RetrievalExecutionState withQuality(RetrievalEvidenceQuality value) {
        return new RetrievalExecutionState(traceId, attempt, budgetUsed, lastAction, emptyResultCount, value);
    }
}
