package com.chatchat.knowledgebase.search;

public record RetrievalBudgetUsage(
    int searchCalls,
    int candidateDocs,
    int rocksdbIter,
    long latencyMs
) {

    public static RetrievalBudgetUsage zero() {
        return new RetrievalBudgetUsage(0, 0, 0, 0L);
    }

    public RetrievalBudgetUsage plus(RetrievalBudgetUsage other) {
        if (other == null) {
            return this;
        }
        return new RetrievalBudgetUsage(
            searchCalls + other.searchCalls(),
            candidateDocs + other.candidateDocs(),
            rocksdbIter + other.rocksdbIter(),
            latencyMs + other.latencyMs()
        );
    }
}
