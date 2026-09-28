package com.chatchat.agents.runtime.evaluation;

/** Release thresholds shared by CI/offline suites and online observation windows. */
public record AgentQualityGateThresholds(
    double minCasePassRate,
    double minRetrievalScore,
    double minToolSelectionScore,
    double minParameterAccuracy,
    double minEvidenceCompleteness
) {
    public AgentQualityGateThresholds {
        minCasePassRate = bounded(minCasePassRate);
        minRetrievalScore = bounded(minRetrievalScore);
        minToolSelectionScore = bounded(minToolSelectionScore);
        minParameterAccuracy = bounded(minParameterAccuracy);
        minEvidenceCompleteness = bounded(minEvidenceCompleteness);
    }

    public static AgentQualityGateThresholds releaseDefaults() {
        return new AgentQualityGateThresholds(1.0D, 0.90D, 0.95D, 0.95D, 0.95D);
    }

    private static double bounded(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
