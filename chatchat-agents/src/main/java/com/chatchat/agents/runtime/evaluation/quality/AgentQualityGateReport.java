package com.chatchat.agents.runtime.evaluation;

import java.util.List;
import java.util.Map;

public record AgentQualityGateReport(
    String contractVersion,
    boolean passed,
    int totalCases,
    int passedCases,
    double casePassRate,
    Map<String, Double> dimensionAverages,
    List<String> failedRunIds,
    List<String> failures
) {
    public static final String CONTRACT_VERSION = "agent_quality_gate_v1";

    public AgentQualityGateReport {
        dimensionAverages = dimensionAverages == null ? Map.of() : Map.copyOf(dimensionAverages);
        failedRunIds = failedRunIds == null ? List.of() : List.copyOf(failedRunIds);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }
}
