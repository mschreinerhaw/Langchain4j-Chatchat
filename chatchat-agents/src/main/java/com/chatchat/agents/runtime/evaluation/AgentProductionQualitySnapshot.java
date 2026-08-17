package com.chatchat.agents.runtime.evaluation;

import java.util.List;
import java.util.Map;

/** Tenant-scoped production quality window derived from durable Agent runs. */
public record AgentProductionQualitySnapshot(
    String contractVersion,
    String tenantId,
    long generatedAt,
    long windowStartedAt,
    int windowHours,
    int sampledRuns,
    int terminalRuns,
    int auditedRuns,
    int applicableAudits,
    Map<String, Double> rates,
    Map<String, Double> measurements,
    Map<String, Long> statusCounts,
    Map<String, Long> claimStatusCounts,
    List<FailureReason> failureReasons,
    List<TrendPoint> trend,
    List<RunQuality> recentFailures
) {

    public static final String CONTRACT_VERSION = "agent_production_quality_v1";

    public AgentProductionQualitySnapshot {
        rates = rates == null ? Map.of() : Map.copyOf(rates);
        measurements = measurements == null ? Map.of() : Map.copyOf(measurements);
        statusCounts = statusCounts == null ? Map.of() : Map.copyOf(statusCounts);
        claimStatusCounts = claimStatusCounts == null ? Map.of() : Map.copyOf(claimStatusCounts);
        failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
        trend = trend == null ? List.of() : List.copyOf(trend);
        recentFailures = recentFailures == null ? List.of() : List.copyOf(recentFailures);
    }

    public record FailureReason(String code, long total, double share) {
    }

    public record TrendPoint(
        long bucketStartedAt,
        int totalRuns,
        int passedAudits,
        int failedAudits,
        double averageClaimCoverage,
        double averageLatencyMs
    ) {
    }

    public record RunQuality(
        String runId,
        String agentId,
        String status,
        long startedAt,
        Long latencyMs,
        String claimStatus,
        Double claimCoverage,
        String groundingStatus,
        double toolSuccessRate,
        int criticalUnboundClaims,
        int unknownReferences,
        List<String> failureReasons
    ) {
        public RunQuality {
            failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
        }
    }
}
