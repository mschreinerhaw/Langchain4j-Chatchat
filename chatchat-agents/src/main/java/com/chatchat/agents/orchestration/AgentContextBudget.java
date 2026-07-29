package com.chatchat.agents.orchestration;

/**
 * Token budget reserved for one Agent model request.
 */
record AgentContextBudget(
    int maxTokens,
    int reservedSystemTokens,
    int reservedHistoryTokens,
    int reservedOutputTokens
) {
    int availableEvidenceTokens() {
        return Math.max(1_000,
            maxTokens - reservedSystemTokens - reservedHistoryTokens - reservedOutputTokens);
    }
}
