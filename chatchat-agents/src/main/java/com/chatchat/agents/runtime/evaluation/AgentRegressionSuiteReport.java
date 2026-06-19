package com.chatchat.agents.runtime.evaluation;

import java.util.List;

public record AgentRegressionSuiteReport(
    String contractVersion,
    Summary summary,
    List<String> hotIssues,
    List<AgentRegressionResult> results
) {

    public static final String CONTRACT_VERSION = "agent_regression_suite_v1";

    public AgentRegressionSuiteReport {
        hotIssues = hotIssues == null ? List.of() : List.copyOf(hotIssues);
        results = results == null ? List.of() : List.copyOf(results);
    }

    public record Summary(
        int total,
        int pass,
        int fail,
        double falseRejectRate
    ) {
    }
}
