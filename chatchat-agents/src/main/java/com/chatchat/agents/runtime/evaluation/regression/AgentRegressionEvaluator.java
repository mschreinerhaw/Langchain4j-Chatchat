package com.chatchat.agents.runtime.evaluation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AgentRegressionEvaluator {

    private static final double DEFAULT_FALSE_REJECT_EVIDENCE_THRESHOLD = 0.5D;
    private static final double DEFAULT_REVIEW_PASS_THRESHOLD = 0.6D;
    private static final double RETRIEVAL_WEIGHT = 0.3D;
    private static final double EVIDENCE_WEIGHT = 0.5D;
    private static final double REVIEW_WEIGHT = 0.2D;
    private final AgentDeterministicScorer deterministicScorer;

    public AgentRegressionEvaluator() {
        this(new AgentDeterministicScorer());
    }

    public AgentRegressionEvaluator(AgentDeterministicScorer deterministicScorer) {
        this.deterministicScorer = deterministicScorer == null ? new AgentDeterministicScorer() : deterministicScorer;
    }

    public AgentRegressionResult evaluate(AgentRegressionCase testCase, AgentRegressionObservation observation) {
        if (testCase == null) {
            throw new IllegalArgumentException("Regression test case is required");
        }
        AgentRegressionObservation actual = observation == null
            ? new AgentRegressionObservation(testCase.id(), List.of(), 0, null, 0, false, true,
                "missing regression observation", 0.0D, null, "")
            : observation;
        String corpus = corpus(actual);

        KeywordCheck retrievalCheck = keywordCheck(corpus, testCase.expected().retrieval().mustContain());
        boolean retrievalHit = actual.chunkCount() > 0 && retrievalCheck.missing().isEmpty();

        AgentDeterministicScore deterministicScore = deterministicScorer.score(testCase, actual);
        double evidenceScore = actual.evidenceScore() == null ? deterministicScore.score() : actual.evidenceScore();
        double minEvidenceScore = testCase.expected().evidence().minScore() == null
            ? 0.0D
            : testCase.expected().evidence().minScore();
        boolean evidencePass = actual.chunksUsed() >= testCase.expected().evidence().minChunks()
            && deterministicScore.missingKeywords().isEmpty()
            && evidenceScore >= minEvidenceScore;

        double reviewScore = actual.reviewScore() == null
            ? (Boolean.TRUE.equals(actual.reviewPassed()) && !actual.reviewRejected() ? 1.0D : 0.0D)
            : actual.reviewScore();
        double minReviewScore = testCase.expected().review().minScore() == null
            ? DEFAULT_REVIEW_PASS_THRESHOLD
            : testCase.expected().review().minScore();
        boolean reviewPass = !testCase.expected().review().mustPass()
            || (Boolean.TRUE.equals(actual.reviewPassed()) && !actual.reviewRejected() && reviewScore >= minReviewScore);
        if (testCase.expected().review().maxRejectRate() != null && actual.reviewRejectRate() != null) {
            reviewPass = reviewPass && actual.reviewRejectRate() <= testCase.expected().review().maxRejectRate();
        }

        boolean falseReject = retrievalHit
            && evidenceScore > DEFAULT_FALSE_REJECT_EVIDENCE_THRESHOLD
            && actual.reviewRejected();

        KeywordCheck answerCheck = keywordCheck(actual.answer(), testCase.expected().answer().mustContainAny());
        boolean answerValid = testCase.expected().answer().mustContainAny().isEmpty() || !answerCheck.matched().isEmpty();

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("retrievalScore", retrievalHit ? 1.0D : 0.0D);
        metrics.put("evidenceScore", evidenceScore);
        metrics.put("reviewScore", reviewScore);
        metrics.put("answerScore", answerValid ? 1.0D : 0.0D);
        metrics.put("overallScore", weightedScore(retrievalHit, evidenceScore, reviewScore));
        metrics.put("falseReject", falseReject ? 1.0D : 0.0D);
        metrics.putAll(deterministicScore.metrics());

        List<String> notes = notes(retrievalHit, evidencePass, reviewPass, answerValid, falseReject, actual);
        boolean passed = retrievalHit && evidencePass && reviewPass && answerValid && !falseReject;
        return new AgentRegressionResult(
            AgentRegressionResult.CONTRACT_VERSION,
            firstText(testCase.id(), actual.caseId()),
            passed ? AgentRegressionResult.PASS : AgentRegressionResult.FAIL,
            new AgentRegressionResult.RetrievalResult(
                retrievalHit,
                actual.chunkCount(),
                retrievalCheck.matched(),
                retrievalCheck.missing()
            ),
            new AgentRegressionResult.EvidenceResult(
                evidenceScore,
                actual.chunksUsed(),
                deterministicScore.matchedKeywords(),
                deterministicScore.missingKeywords(),
                deterministicScore.graph()
            ),
            new AgentRegressionResult.ReviewResult(
                reviewPass,
                actual.reviewRejected(),
                actual.reviewReason(),
                reviewScore,
                falseReject
            ),
            new AgentRegressionResult.AnswerResult(
                answerValid,
                answerCheck.matched(),
                testCase.expected().answer().mustContainAny()
            ),
            metrics,
            notes
        );
    }

    public AgentRegressionSuiteReport summarize(List<AgentRegressionResult> results) {
        List<AgentRegressionResult> safeResults = results == null ? List.of() : List.copyOf(results);
        int pass = (int) safeResults.stream()
            .filter(result -> result != null && AgentRegressionResult.PASS.equals(result.status()))
            .count();
        int falseRejects = (int) safeResults.stream()
            .filter(result -> result != null && result.review() != null && result.review().falseReject())
            .count();
        List<String> hotIssues = new ArrayList<>();
        if (falseRejects > 0) {
            hotIssues.add("review overly strict on cases with usable evidence");
            hotIssues.add("missing or weak multi-chunk aggregation before review");
        }
        int fail = safeResults.size() - pass;
        return new AgentRegressionSuiteReport(
            AgentRegressionSuiteReport.CONTRACT_VERSION,
            new AgentRegressionSuiteReport.Summary(
                safeResults.size(),
                pass,
                fail,
                ratio(falseRejects, safeResults.size())
            ),
            hotIssues,
            safeResults
        );
    }

    private String corpus(AgentRegressionObservation observation) {
        return String.join("\n", observation.retrievalTexts() == null ? List.of() : observation.retrievalTexts());
    }

    private KeywordCheck keywordCheck(String text, List<String> keywords) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String keyword : keywords == null ? List.<String>of() : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                matched.add(keyword);
            } else {
                missing.add(keyword);
            }
        }
        return new KeywordCheck(matched, missing);
    }

    private List<String> notes(boolean retrievalHit,
                               boolean evidencePass,
                               boolean reviewPass,
                               boolean answerValid,
                               boolean falseReject,
                               AgentRegressionObservation observation) {
        List<String> notes = new ArrayList<>();
        if (!retrievalHit) {
            notes.add("retrieval did not satisfy expected hits");
        }
        if (!evidencePass) {
            notes.add("evidence did not satisfy expected completeness");
        }
        if (!reviewPass) {
            notes.add("review did not satisfy expected pass criteria");
        }
        if (!answerValid) {
            notes.add("answer did not contain expected content");
        }
        if (falseReject) {
            notes.add("false_reject_detection");
        }
        if (observation.reviewReason() != null && !observation.reviewReason().isBlank()) {
            notes.add("review_reason: " + observation.reviewReason());
        }
        return notes;
    }

    private double weightedScore(boolean retrievalHit, double evidenceScore, double reviewScore) {
        return (retrievalHit ? RETRIEVAL_WEIGHT : 0.0D)
            + bounded(evidenceScore) * EVIDENCE_WEIGHT
            + bounded(reviewScore) * REVIEW_WEIGHT;
    }

    private double bounded(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 1.0D : ((double) numerator) / denominator;
    }

    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private record KeywordCheck(List<String> matched, List<String> missing) {
    }
}
