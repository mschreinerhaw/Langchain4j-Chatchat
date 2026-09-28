package com.chatchat.agents.runtime.evaluation;

import com.chatchat.agents.runtime.evaluation.AgentEvaluationCase.RetrievalExpectation;
import com.chatchat.agents.runtime.evaluation.AgentEvaluationCase.ToolExpectation;
import com.chatchat.agents.runtime.evaluation.AgentEvaluationReport.QualityDimension;
import com.chatchat.agents.runtime.trace.AgentRunTrace;
import com.chatchat.agents.runtime.trace.EvidenceTrace;
import com.chatchat.agents.runtime.trace.ToolCallTrace;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Scores persisted online traces and offline gold cases with the same contract. */
@Component
public class AgentEvaluationService {

    public AgentEvaluationReport evaluate(AgentRunTrace trace, AgentEvaluationCase evaluationCase) {
        if (trace == null) {
            throw new IllegalArgumentException("Agent run trace is required");
        }
        AgentEvaluationCase criteria = evaluationCase == null
            ? new AgentEvaluationCase(trace.question(), List.of(), List.of(), true)
            : evaluationCase;

        List<EvidenceTrace> evidence = trace.evidence() == null ? List.of() : trace.evidence();
        List<ToolCallTrace> toolCalls = trace.toolCalls() == null ? List.of() : trace.toolCalls();
        Set<String> availableEvidence = evidence.stream()
            .map(EvidenceTrace::refId)
            .filter(this::hasText)
            .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        Set<String> usedEvidence = trace.answer() == null || trace.answer().citations() == null
            ? Set.of()
            : trace.answer().citations().stream()
                .map(item -> item.get("refId"))
                .filter(this::hasText)
                .map(String::valueOf)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);

        List<String> expectedEvidence = expectedEvidence(criteria);
        List<String> matchedEvidence = intersection(expectedEvidence, availableEvidence);
        List<String> missingEvidence = difference(expectedEvidence, availableEvidence);
        List<String> citationHits = intersection(expectedEvidence, usedEvidence);
        KeywordMatch keywordMatch = keywordMatch(trace.answer() == null ? "" : trace.answer().answer(), criteria.expectedKeywords());

        RetrievalScore retrieval = retrievalScore(criteria, evidence);
        ToolScore toolSelection = toolSelectionScore(criteria.expectedTools(), toolCalls);
        ParameterScore parameters = parameterScore(criteria.expectedTools(), toolCalls);

        double evidenceHitRate = ratio(matchedEvidence.size(), expectedEvidence.size());
        double citationHitRate = !criteria.mustHaveCitation()
            ? 1.0D
            : expectedEvidence.isEmpty()
            ? (!criteria.mustHaveCitation() || !usedEvidence.isEmpty() ? 1.0D : 0.0D)
            : ratio(citationHits.size(), expectedEvidence.size());
        double toolEvidenceCoverage = toolEvidenceCoverage(criteria.expectedTools(), toolCalls, evidence);
        double supportedCitationRate = supportedCitationRate(usedEvidence, availableEvidence);
        double evidenceCompleteness = average(evidenceHitRate, citationHitRate, toolEvidenceCoverage);
        double keywordCoverage = ratio(keywordMatch.matched().size(), criteria.expectedKeywords().size());
        double groundingPassRate = "grounded".equalsIgnoreCase(trace.grounding() == null ? null : trace.grounding().status()) ? 1.0D : 0.0D;
        double zeroHitRate = availableEvidence.isEmpty() ? 1.0D : 0.0D;
        double governanceViolationRate = governanceViolationRate(trace);
        double overallScore = average(retrieval.score(), toolSelection.f1(), parameters.score(), evidenceCompleteness);

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("retrievalPrecision", retrieval.precision());
        metrics.put("retrievalRecall", retrieval.recall());
        metrics.put("retrievalReciprocalRank", retrieval.reciprocalRank());
        metrics.put("retrievalScore", retrieval.score());
        metrics.put("toolSelectionPrecision", toolSelection.precision());
        metrics.put("toolSelectionRecall", toolSelection.recall());
        metrics.put("toolSelectionF1", toolSelection.f1());
        metrics.put("parameterAccuracy", parameters.score());
        metrics.put("evidenceHitRate", evidenceHitRate);
        metrics.put("citationHitRate", citationHitRate);
        metrics.put("toolEvidenceCoverage", toolEvidenceCoverage);
        metrics.put("supportedCitationRate", supportedCitationRate);
        metrics.put("evidenceCompleteness", evidenceCompleteness);
        metrics.put("answerKeywordCoverage", keywordCoverage);
        metrics.put("groundingPassRate", groundingPassRate);
        metrics.put("zeroHitRate", zeroHitRate);
        metrics.put("governanceViolationRate", governanceViolationRate);
        metrics.put("overallScore", overallScore);

        AgentEvaluationCase.Thresholds thresholds = criteria.thresholds();
        Map<String, QualityDimension> dimensions = new LinkedHashMap<>();
        dimensions.put("retrieval", dimension(retrieval.score(), thresholds.minRetrievalScore(),
            retrieval.expectedCount(), evidence.size(), retrieval.details()));
        dimensions.put("toolSelection", dimension(toolSelection.f1(), thresholds.minToolSelectionScore(),
            criteria.expectedTools().size(), toolCalls.size(), toolSelection.details()));
        dimensions.put("parameterAccuracy", dimension(parameters.score(), thresholds.minParameterAccuracy(),
            parameters.expectedCount(), parameters.actualCount(), parameters.details()));
        dimensions.put("evidenceCompleteness", dimension(evidenceCompleteness, thresholds.minEvidenceCompleteness(),
            Math.max(expectedEvidence.size(), Math.max(criteria.expectedTools().size(), criteria.mustHaveCitation() ? 1 : 0)),
            availableEvidence.size(), evidenceDetails(missingEvidence, usedEvidence,
                availableEvidence, toolEvidenceCoverage)));

        boolean dimensionsPassed = dimensions.values().stream().allMatch(QualityDimension::passed);
        boolean passed = dimensionsPassed
            && overallScore >= thresholds.minOverallScore()
            && keywordMatch.missing().isEmpty()
            && (!criteria.mustHaveCitation() || !usedEvidence.isEmpty())
            && supportedCitationRate == 1.0D
            && groundingPassRate == 1.0D
            && governanceViolationRate == 0.0D;

        List<String> notes = notes(criteria, usedEvidence, missingEvidence, keywordMatch.missing(), trace,
            dimensions, supportedCitationRate, overallScore);
        return new AgentEvaluationReport(
            AgentEvaluationReport.CONTRACT_VERSION,
            trace.runId(),
            firstText(criteria.question(), trace.question()),
            passed,
            metrics,
            dimensions,
            matchedEvidence,
            missingEvidence,
            keywordMatch.matched(),
            keywordMatch.missing(),
            notes
        );
    }

    private RetrievalScore retrievalScore(AgentEvaluationCase criteria, List<EvidenceTrace> actual) {
        List<RetrievalExpectation> expected = criteria.expectedRetrieval().isEmpty()
            ? criteria.expectedEvidence().stream().map(ref -> new RetrievalExpectation(ref, List.of(), null)).toList()
            : criteria.expectedRetrieval();
        if (expected.isEmpty()) {
            return new RetrievalScore(1.0D, 1.0D, 1.0D, 1.0D, 0, List.of("not evaluated: no retrieval gold"));
        }

        Set<Integer> relevantRanks = new LinkedHashSet<>();
        List<String> details = new ArrayList<>();
        for (RetrievalExpectation gold : expected) {
            int rank = findEvidenceRank(gold, actual);
            if (rank > 0 && rank <= gold.maxRank()) {
                relevantRanks.add(rank);
            } else if (rank > gold.maxRank()) {
                details.add(label(gold) + " returned below maxRank=" + gold.maxRank());
            } else {
                details.add("missing relevant result: " + label(gold));
            }
        }
        double precision = ratio(relevantRanks.size(), actual.size());
        double recall = ratio(relevantRanks.size(), expected.size());
        double reciprocalRank = relevantRanks.stream().mapToInt(Integer::intValue).min().isPresent()
            ? 1.0D / relevantRanks.stream().mapToInt(Integer::intValue).min().orElse(1)
            : 0.0D;
        double score = average(precision, recall, reciprocalRank);
        if (actual.size() > relevantRanks.size()) {
            details.add((actual.size() - relevantRanks.size()) + " irrelevant retrieval result(s)");
        }
        return new RetrievalScore(precision, recall, reciprocalRank, score, expected.size(), details);
    }

    private int findEvidenceRank(RetrievalExpectation expected, List<EvidenceTrace> evidence) {
        for (int i = 0; i < evidence.size(); i++) {
            EvidenceTrace item = evidence.get(i);
            if (item == null) {
                continue;
            }
            if (hasText(expected.refId()) && expected.refId().equals(item.refId())) {
                return i + 1;
            }
            String corpus = evidenceCorpus(item);
            if (!expected.mustContainAny().isEmpty() && expected.mustContainAny().stream()
                .filter(this::hasText)
                .anyMatch(term -> corpus.contains(term.toLowerCase(Locale.ROOT)))) {
                return i + 1;
            }
        }
        return -1;
    }

    private String evidenceCorpus(EvidenceTrace item) {
        return String.join("\n",
                text(item.refId()), text(item.source()), text(item.contentPreview()), text(item.metadata()))
            .toLowerCase(Locale.ROOT);
    }

    private ToolScore toolSelectionScore(List<ToolExpectation> expected, List<ToolCallTrace> actual) {
        if (expected.isEmpty()) {
            return new ToolScore(1.0D, 1.0D, 1.0D, List.of("not evaluated: no tool gold"));
        }
        Set<Integer> matched = new LinkedHashSet<>();
        List<String> details = new ArrayList<>();
        for (ToolExpectation gold : expected) {
            int index = findUnusedTool(gold.toolName(), actual, matched);
            if (index >= 0) {
                matched.add(index);
            } else {
                details.add("missing expected tool: " + gold.toolName());
            }
        }
        double precision = ratio(matched.size(), actual.size());
        double recall = ratio(matched.size(), expected.size());
        double f1 = (precision + recall) == 0.0D ? 0.0D : 2.0D * precision * recall / (precision + recall);
        if (actual.size() > matched.size()) {
            details.add((actual.size() - matched.size()) + " unexpected tool call(s)");
        }
        return new ToolScore(precision, recall, f1, details);
    }

    private ParameterScore parameterScore(List<ToolExpectation> expected, List<ToolCallTrace> actual) {
        int expectedLeaves = 0;
        int matchedLeaves = 0;
        int actualLeaves = 0;
        Set<Integer> used = new LinkedHashSet<>();
        List<String> details = new ArrayList<>();
        for (ToolExpectation gold : expected) {
            Map<String, Object> expectedFlat = flatten(gold.expectedArguments());
            expectedLeaves += expectedFlat.size();
            int index = findUnusedTool(gold.toolName(), actual, used);
            if (index < 0) {
                for (String path : expectedFlat.keySet()) {
                    details.add(gold.toolName() + "." + path + " missing because tool was not called");
                }
                continue;
            }
            used.add(index);
            Map<String, Object> actualFlat = flatten(actual.get(index).input());
            actualLeaves += actualFlat.size();
            for (Map.Entry<String, Object> entry : expectedFlat.entrySet()) {
                if (valueEquals(entry.getValue(), actualFlat.get(entry.getKey()))) {
                    matchedLeaves++;
                } else {
                    details.add(gold.toolName() + "." + entry.getKey() + " expected=" + entry.getValue()
                        + " actual=" + actualFlat.get(entry.getKey()));
                }
            }
        }
        return new ParameterScore(ratio(matchedLeaves, expectedLeaves), expectedLeaves, actualLeaves, details);
    }

    private Map<String, Object> flatten(Map<String, ?> source) {
        Map<String, Object> flattened = new LinkedHashMap<>();
        flattenInto("", source == null ? Map.of() : source, flattened);
        return flattened;
    }

    private void flattenInto(String prefix, Object value, Map<String, Object> target) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String path = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
                flattenInto(path, entry.getValue(), target);
            }
            return;
        }
        if (!prefix.isEmpty()) {
            target.put(prefix, value);
        }
    }

    private boolean valueEquals(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected instanceof Number && actual instanceof Number) {
            try {
                return new BigDecimal(String.valueOf(expected)).compareTo(new BigDecimal(String.valueOf(actual))) == 0;
            } catch (NumberFormatException ignored) {
                // Fall through to ordinary equality.
            }
        }
        if (expected instanceof CharSequence || actual instanceof CharSequence) {
            return String.valueOf(expected).trim().equals(String.valueOf(actual).trim());
        }
        return expected.equals(actual);
    }

    private int findUnusedTool(String expectedName, List<ToolCallTrace> actual, Set<Integer> used) {
        for (int i = 0; i < actual.size(); i++) {
            if (!used.contains(i) && actual.get(i) != null && toolNameMatches(expectedName, actual.get(i).toolName())) {
                return i;
            }
        }
        return -1;
    }

    private boolean toolNameMatches(String expected, String actual) {
        if (!hasText(expected) || !hasText(actual)) {
            return false;
        }
        String left = expected.toLowerCase(Locale.ROOT);
        String right = actual.toLowerCase(Locale.ROOT);
        return left.equals(right) || right.endsWith("_" + left) || right.endsWith("." + left);
    }

    private double toolEvidenceCoverage(List<ToolExpectation> expected, List<ToolCallTrace> calls, List<EvidenceTrace> evidence) {
        if (expected.isEmpty()) {
            return 1.0D;
        }
        int covered = 0;
        Set<Integer> used = new LinkedHashSet<>();
        for (ToolExpectation gold : expected) {
            int index = findUnusedTool(gold.toolName(), calls, used);
            if (index < 0) {
                continue;
            }
            used.add(index);
            ToolCallTrace call = calls.get(index);
            boolean hasEvidence = hasText(call.evidenceId()) || evidence.stream().anyMatch(item -> item != null
                && toolNameMatches(call.toolName(), item.toolName()));
            if (hasEvidence) {
                covered++;
            }
        }
        return ratio(covered, expected.size());
    }

    private double supportedCitationRate(Set<String> used, Set<String> available) {
        if (used.isEmpty()) {
            return 1.0D;
        }
        return ratio((int) used.stream().filter(available::contains).count(), used.size());
    }

    private List<String> evidenceDetails(List<String> missing, Set<String> used, Set<String> available,
                                         double toolEvidenceCoverage) {
        List<String> details = new ArrayList<>();
        missing.forEach(ref -> details.add("missing evidence: " + ref));
        used.stream().filter(ref -> !available.contains(ref)).forEach(ref -> details.add("unsupported citation: " + ref));
        if (toolEvidenceCoverage < 1.0D) {
            details.add("one or more expected tool calls produced no traceable evidence");
        }
        return details;
    }

    private QualityDimension dimension(double score, double threshold, int expected, int actual, List<String> details) {
        return new QualityDimension(score, score >= threshold, expected, actual, details);
    }

    private List<String> expectedEvidence(AgentEvaluationCase criteria) {
        LinkedHashSet<String> refs = new LinkedHashSet<>(criteria.expectedEvidence());
        criteria.expectedRetrieval().stream().map(RetrievalExpectation::refId).filter(this::hasText).forEach(refs::add);
        return List.copyOf(refs);
    }

    private KeywordMatch keywordMatch(String answer, List<String> expectedKeywords) {
        String normalizedAnswer = text(answer).toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String keyword : expectedKeywords) {
            if (!hasText(keyword)) {
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

    private List<String> notes(AgentEvaluationCase criteria, Set<String> usedEvidence, List<String> missingEvidence,
                               List<String> missingKeywords, AgentRunTrace trace,
                               Map<String, QualityDimension> dimensions, double supportedCitationRate,
                               double overallScore) {
        List<String> notes = new ArrayList<>();
        if (!missingEvidence.isEmpty()) notes.add("expected evidence was not returned");
        if (criteria.mustHaveCitation() && usedEvidence.isEmpty()) notes.add("answer did not cite evidence");
        if (supportedCitationRate < 1.0D) notes.add("answer cited evidence that was not returned");
        if (!missingKeywords.isEmpty()) notes.add("answer missed expected keywords");
        if (trace.grounding() != null && !"grounded".equalsIgnoreCase(trace.grounding().status())) notes.add("grounding did not pass");
        if (governanceViolationRate(trace) > 0.0D) notes.add("tool governance violation detected");
        dimensions.forEach((name, value) -> {
            if (!value.passed()) notes.add(name + " score was below threshold");
        });
        if (overallScore < criteria.thresholds().minOverallScore()) notes.add("overall score was below threshold");
        return notes;
    }

    private double governanceViolationRate(AgentRunTrace trace) {
        if (trace.toolCalls() == null || trace.toolCalls().isEmpty()) return 0.0D;
        long governed = trace.toolCalls().stream()
            .filter(call -> call != null && call.governance() != null && !call.governance().isEmpty()).count();
        if (governed == 0) return 0.0D;
        long violations = trace.toolCalls().stream()
            .filter(call -> call != null && governanceViolation(call.success(), call.governance())).count();
        return ((double) violations) / governed;
    }

    private boolean governanceViolation(Boolean success, Map<String, Object> governance) {
        if (!Boolean.TRUE.equals(success) || governance == null || governance.isEmpty()) return false;
        String decision = text(governance.getOrDefault("policyDecision", "")).toUpperCase(Locale.ROOT);
        if ("BLOCK".equals(decision)) return true;
        boolean required = Boolean.parseBoolean(text(governance.getOrDefault("confirmRequired", false)));
        boolean confirmed = Boolean.parseBoolean(text(governance.getOrDefault("confirmed", false)));
        return "REQUIRE_CONFIRM".equals(decision) && required && !confirmed;
    }

    private List<String> intersection(List<String> expected, Set<String> actual) {
        return expected.stream().filter(value -> value != null && actual.contains(value)).toList();
    }

    private List<String> difference(List<String> expected, Set<String> actual) {
        return expected.stream().filter(value -> value != null && !actual.contains(value)).toList();
    }

    private double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 1.0D : ((double) numerator) / denominator;
    }

    private double average(double... values) {
        if (values == null || values.length == 0) return 0.0D;
        double total = 0.0D;
        for (double value : values) total += value;
        return total / values.length;
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstText(String first, String second) {
        return !hasText(first) ? second : first;
    }

    private String label(RetrievalExpectation expectation) {
        return hasText(expectation.refId()) ? expectation.refId() : String.join("|", expectation.mustContainAny());
    }

    private record KeywordMatch(List<String> matched, List<String> missing) {}
    private record RetrievalScore(double precision, double recall, double reciprocalRank, double score,
                                  int expectedCount, List<String> details) {}
    private record ToolScore(double precision, double recall, double f1, List<String> details) {}
    private record ParameterScore(double score, int expectedCount, int actualCount, List<String> details) {}
}
