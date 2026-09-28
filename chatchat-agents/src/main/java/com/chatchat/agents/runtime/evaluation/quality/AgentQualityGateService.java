package com.chatchat.agents.runtime.evaluation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates case reports into a deterministic release/online-window quality gate. */
@Component
public class AgentQualityGateService {

    public AgentQualityGateReport evaluate(List<AgentEvaluationReport> reports,
                                           AgentQualityGateThresholds thresholds) {
        List<AgentEvaluationReport> cases = reports == null ? List.of() : reports.stream()
            .filter(report -> report != null)
            .toList();
        AgentQualityGateThresholds gate = thresholds == null
            ? AgentQualityGateThresholds.releaseDefaults() : thresholds;

        int passedCases = (int) cases.stream().filter(AgentEvaluationReport::passed).count();
        double passRate = ratio(passedCases, cases.size());
        Map<String, Double> averages = new LinkedHashMap<>();
        averages.put("retrieval", dimensionAverage(cases, "retrieval"));
        averages.put("toolSelection", dimensionAverage(cases, "toolSelection"));
        averages.put("parameterAccuracy", dimensionAverage(cases, "parameterAccuracy"));
        averages.put("evidenceCompleteness", dimensionAverage(cases, "evidenceCompleteness"));

        List<String> failures = new ArrayList<>();
        check(failures, "casePassRate", passRate, gate.minCasePassRate());
        check(failures, "retrieval", averages.get("retrieval"), gate.minRetrievalScore());
        check(failures, "toolSelection", averages.get("toolSelection"), gate.minToolSelectionScore());
        check(failures, "parameterAccuracy", averages.get("parameterAccuracy"), gate.minParameterAccuracy());
        check(failures, "evidenceCompleteness", averages.get("evidenceCompleteness"), gate.minEvidenceCompleteness());
        for (String dimension : averages.keySet()) {
            if (!hasEvaluatedCase(cases, dimension)) {
                failures.add(dimension + " has no gold-labelled cases");
            }
        }
        if (cases.isEmpty()) {
            failures.add("evaluation suite contains no cases");
        }

        return new AgentQualityGateReport(
            AgentQualityGateReport.CONTRACT_VERSION,
            failures.isEmpty(),
            cases.size(),
            passedCases,
            passRate,
            averages,
            cases.stream().filter(report -> !report.passed()).map(AgentEvaluationReport::runId).toList(),
            failures
        );
    }

    private double dimensionAverage(List<AgentEvaluationReport> reports, String dimension) {
        return reports.stream()
            .map(report -> report.dimensions().get(dimension))
            .filter(value -> value != null && value.expectedCount() > 0)
            .mapToDouble(AgentEvaluationReport.QualityDimension::score)
            .average()
            .orElse(0.0D);
    }

    private boolean hasEvaluatedCase(List<AgentEvaluationReport> reports, String dimension) {
        return reports.stream().map(report -> report.dimensions().get(dimension))
            .anyMatch(value -> value != null && value.expectedCount() > 0);
    }

    private void check(List<String> failures, String metric, double actual, double expected) {
        if (actual < expected) {
            failures.add(metric + "=" + actual + " below threshold=" + expected);
        }
    }

    private double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 0.0D : ((double) numerator) / denominator;
    }
}
