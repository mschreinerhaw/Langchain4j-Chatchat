package com.chatchat.agents.orchestration.planning.model;

/**
 * Token budget reserved for one Agent model request.
 */
public record AgentContextBudget(
    int maxTokens,
    int reservedSystemTokens,
    int reservedHistoryTokens,
    int reservedOutputTokens
) {
    public int availableEvidenceTokens() {
        return Math.max(1_000,
            maxTokens - reservedSystemTokens - reservedHistoryTokens - reservedOutputTokens);
    }
}
