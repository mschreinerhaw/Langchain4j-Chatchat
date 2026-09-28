package com.chatchat.agents.runtime.evaluation;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pairwise evaluator that hides product/version identity from the judge. */
@Component
public class BlindAnswerPairEvaluator {
    private static final List<String> QUALITY_DIMENSIONS = List.of(
        "correctness", "completeness", "citationAccuracy", "directUsefulness");

    public CaseResult evaluate(Case testCase, Judge judge) {
        if (testCase == null || judge == null) throw new IllegalArgumentException("case and judge are required");
        boolean swap = stableSwap(testCase.id());
        Candidate first = swap ? testCase.candidateB() : testCase.candidateA();
        Candidate second = swap ? testCase.candidateA() : testCase.candidateB();
        Judgement blind = judge.judge(new BlindCase(testCase.id(), testCase.question(), testCase.evidence(),
            new BlindCandidate("A", first.answer()), new BlindCandidate("B", second.answer())));
        CandidateScore firstScore = normalize(blind.candidateA(), first.latencyMs());
        CandidateScore secondScore = normalize(blind.candidateB(), second.latencyMs());
        CandidateScore a = swap ? secondScore : firstScore;
        CandidateScore b = swap ? firstScore : secondScore;
        String winner = compare(a, b);
        return new CaseResult(testCase.id(), testCase.candidateA().id(), testCase.candidateB().id(),
            a, b, winner, blind.rationale());
    }

    public SuiteResult summarize(List<CaseResult> results) {
        List<CaseResult> safe = results == null ? List.of() : results.stream().filter(r -> r != null).toList();
        Map<String, Aggregate> products = new LinkedHashMap<>();
        for (CaseResult result : safe) {
            accumulate(products, result.candidateAId(), result.candidateA(), result.winner().equals(result.candidateAId()));
            accumulate(products, result.candidateBId(), result.candidateB(), result.winner().equals(result.candidateBId()));
        }
        return new SuiteResult(safe.size(), Map.copyOf(products), safe);
    }

    private void accumulate(Map<String, Aggregate> aggregates, String id, CandidateScore score, boolean win) {
        Aggregate old = aggregates.getOrDefault(id, new Aggregate(0, 0, 0, 0, 0, 0, 0));
        int count = old.count() + 1;
        aggregates.put(id, new Aggregate(count, old.wins() + (win ? 1 : 0),
            average(old.correctness(), old.count(), score.correctness()),
            average(old.completeness(), old.count(), score.completeness()),
            average(old.citationAccuracy(), old.count(), score.citationAccuracy()),
            average(old.directUsefulness(), old.count(), score.directUsefulness()),
            average(old.latencyMs(), old.count(), score.latencyMs())));
    }

    private double average(double old, int count, double value) { return ((old * count) + value) / (count + 1); }

    private CandidateScore normalize(Map<String, Double> scores, long latencyMs) {
        Map<String, Double> safe = scores == null ? Map.of() : scores;
        return new CandidateScore(value(safe, "correctness"), value(safe, "completeness"),
            value(safe, "citationAccuracy"), value(safe, "directUsefulness"), Math.max(0, latencyMs));
    }

    private double value(Map<String, Double> scores, String key) {
        return Math.max(0D, Math.min(1D, scores.getOrDefault(key, 0D)));
    }

    private String compare(CandidateScore a, CandidateScore b) {
        double delta = a.qualityScore() - b.qualityScore();
        if (Math.abs(delta) < 0.0001D) return "TIE";
        return delta > 0 ? "A" : "B";
    }

    private boolean stableSwap(String id) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(id).getBytes(StandardCharsets.UTF_8));
            return (hash[0] & 1) == 1;
        } catch (Exception ignored) {
            return String.valueOf(id).hashCode() % 2 != 0;
        }
    }

    @FunctionalInterface public interface Judge { Judgement judge(BlindCase testCase); }
    public record Case(String id, String question, List<String> evidence, Candidate candidateA, Candidate candidateB) {
        public Case { evidence = evidence == null ? List.of() : List.copyOf(evidence); }
    }
    public record Candidate(String id, String answer, long latencyMs) {}
    public record BlindCase(String id, String question, List<String> evidence,
                            BlindCandidate candidateA, BlindCandidate candidateB) {}
    public record BlindCandidate(String label, String answer) {}
    public record Judgement(Map<String, Double> candidateA, Map<String, Double> candidateB, String rationale) {}
    public record CandidateScore(double correctness, double completeness, double citationAccuracy,
                                 double directUsefulness, long latencyMs) {
        public double qualityScore() { return (correctness + completeness + citationAccuracy + directUsefulness) / 4D; }
    }
    public record CaseResult(String caseId, String candidateAId, String candidateBId,
                             CandidateScore candidateA, CandidateScore candidateB, String blindWinner,
                             String rationale) {
        public String winner() {
            if ("TIE".equals(blindWinner)) return "TIE";
            return "A".equals(blindWinner) ? candidateAId : candidateBId;
        }
    }
    public record Aggregate(int count, int wins, double correctness, double completeness,
                            double citationAccuracy, double directUsefulness, double latencyMs) {}
    public record SuiteResult(int caseCount, Map<String, Aggregate> candidates, List<CaseResult> cases) {}
}
