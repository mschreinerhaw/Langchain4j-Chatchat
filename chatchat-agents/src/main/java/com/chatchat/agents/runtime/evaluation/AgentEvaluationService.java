package com.chatchat.agents.runtime.evaluation;

import com.chatchat.agents.runtime.trace.AgentRunTrace;
import com.chatchat.agents.runtime.trace.EvidenceTrace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class AgentEvaluationService {

    public AgentEvaluationReport evaluate(AgentRunTrace trace, AgentEvaluationCase evaluationCase) {
        if (trace == null) {
            throw new IllegalArgumentException("Agent run trace is required");
        }
        AgentEvaluationCase criteria = evaluationCase == null
            ? new AgentEvaluationCase(trace.question(), List.of(), List.of(), true)
            : evaluationCase;
        Set<String> availableEvidence = trace.evidence().stream()
            .map(EvidenceTrace::refId)
            .filter(value -> value != null && !value.isBlank())
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        Set<String> usedEvidence = trace.answer() == null || trace.answer().citations() == null
            ? Set.of()
            : trace.answer().citations().stream()
                .map(item -> item.get("refId"))
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .map(String::valueOf)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        List<String> matchedEvidence = intersection(criteria.expectedEvidence(), availableEvidence);
        List<String> missingEvidence = difference(criteria.expectedEvidence(), availableEvidence);
        List<String> citationHits = intersection(criteria.expectedEvidence(), usedEvidence);
        KeywordMatch keywordMatch = keywordMatch(trace.answer() == null ? "" : trace.answer().answer(), criteria.expectedKeywords());
        double evidenceHitRate = ratio(matchedEvidence.size(), criteria.expectedEvidence().size());
        double citationHitRate = criteria.expectedEvidence().isEmpty()
            ? (usedEvidence.isEmpty() && criteria.mustHaveCitation() ? 0.0 : 1.0)
            : ratio(citationHits.size(), criteria.expectedEvidence().size());
        double keywordCoverage = ratio(keywordMatch.matched().size(), criteria.expectedKeywords().size());
        double groundingPassRate = "grounded".equalsIgnoreCase(trace.grounding() == null ? null : trace.grounding().status()) ? 1.0 : 0.0;
        double zeroHitRate = availableEvidence.isEmpty() ? 1.0 : 0.0;
        double governanceViolationRate = governanceViolationRate(trace);

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("evidenceHitRate", evidenceHitRate);
        metrics.put("citationHitRate", citationHitRate);
        metrics.put("answerKeywordCoverage", keywordCoverage);
        metrics.put("groundingPassRate", groundingPassRate);
        metrics.put("zeroHitRate", zeroHitRate);
        metrics.put("governanceViolationRate", governanceViolationRate);
        metrics.put("overallScore", average(
            evidenceHitRate,
            citationHitRate,
            keywordCoverage,
            groundingPassRate,
            1.0 - governanceViolationRate
        ));

        List<String> notes = notes(criteria, usedEvidence, missingEvidence, keywordMatch.missing(), trace);
        boolean passed = missingEvidence.isEmpty()
            && keywordMatch.missing().isEmpty()
            && (!criteria.mustHaveCitation() || !usedEvidence.isEmpty())
            && groundingPassRate == 1.0
            && governanceViolationRate == 0.0;
        return new AgentEvaluationReport(
            AgentEvaluationReport.CONTRACT_VERSION,
            trace.runId(),
            firstText(criteria.question(), trace.question()),
            passed,
            metrics,
            matchedEvidence,
            missingEvidence,
            keywordMatch.matched(),
            keywordMatch.missing(),
            notes
        );
    }

    private KeywordMatch keywordMatch(String answer, List<String> expectedKeywords) {
        String normalizedAnswer = String.valueOf(answer == null ? "" : answer).toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String keyword : expectedKeywords == null ? List.<String>of() : expectedKeywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (normalizedAnswer.contains(keyword.toLowerCase(Locale.ROOT))) {
                matched.add(keyword);
            } else {
                missing.add(keyword);
            }
        }
        return new KeywordMatch(matched, missing);
    }

    private List<String> notes(AgentEvaluationCase criteria,
                               Set<String> usedEvidence,
                               List<String> missingEvidence,
                               List<String> missingKeywords,
                               AgentRunTrace trace) {
        List<String> notes = new ArrayList<>();
        if (!missingEvidence.isEmpty()) {
            notes.add("expected evidence was not returned");
        }
        if (criteria.mustHaveCitation() && usedEvidence.isEmpty()) {
            notes.add("answer did not cite evidence");
        }
        if (!missingKeywords.isEmpty()) {
            notes.add("answer missed expected keywords");
        }
        if (trace.grounding() != null && !"grounded".equalsIgnoreCase(trace.grounding().status())) {
            notes.add("grounding did not pass");
        }
        if (governanceViolationRate(trace) > 0.0) {
            notes.add("tool governance violation detected");
        }
        return notes;
    }

    private double governanceViolationRate(AgentRunTrace trace) {
        if (trace.toolCalls() == null || trace.toolCalls().isEmpty()) {
            return 0.0;
        }
        long governed = trace.toolCalls().stream()
            .filter(call -> call != null && call.governance() != null && !call.governance().isEmpty())
            .count();
        if (governed == 0) {
            return 0.0;
        }
        long violations = trace.toolCalls().stream()
            .filter(call -> call != null && governanceViolation(call.success(), call.governance()))
            .count();
        return ((double) violations) / governed;
    }

    private boolean governanceViolation(Boolean success, Map<String, Object> governance) {
        if (!Boolean.TRUE.equals(success) || governance == null || governance.isEmpty()) {
            return false;
        }
        String decision = String.valueOf(governance.getOrDefault("policyDecision", "")).toUpperCase(Locale.ROOT);
        if ("BLOCK".equals(decision)) {
            return true;
        }
        boolean confirmRequired = Boolean.parseBoolean(String.valueOf(governance.getOrDefault("confirmRequired", false)));
        boolean confirmed = Boolean.parseBoolean(String.valueOf(governance.getOrDefault("confirmed", false)));
        return "REQUIRE_CONFIRM".equals(decision) && confirmRequired && !confirmed;
    }

    private List<String> intersection(List<String> expected, Set<String> actual) {
        return expected == null
            ? List.of()
            : expected.stream()
                .filter(value -> value != null && actual.contains(value))
                .toList();
    }

    private List<String> difference(List<String> expected, Set<String> actual) {
        return expected == null
            ? List.of()
            : expected.stream()
                .filter(value -> value != null && !actual.contains(value))
                .toList();
    }

    private double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 1.0 : ((double) numerator) / denominator;
    }

    private double average(double... values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total / values.length;
    }

    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private record KeywordMatch(List<String> matched, List<String> missing) {
    }
}
