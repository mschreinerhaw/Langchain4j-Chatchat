package com.chatchat.knowledgebase.search;

public record RetrievalBudgetReservation(
    int searchCalls,
    int candidateDocs,
    int rocksdbIter,
    long latencyMs
) {

    public RetrievalBudgetUsage toUsage() {
        return new RetrievalBudgetUsage(searchCalls, candidateDocs, rocksdbIter, latencyMs);
    }
}
