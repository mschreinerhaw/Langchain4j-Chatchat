package com.chatchat.agents.runtime.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlindAnswerPairEvaluatorTest {
    @Test
    void hidesProductLabelsAndMapsScoresBackToCandidates() {
        BlindAnswerPairEvaluator evaluator = new BlindAnswerPairEvaluator();
        BlindAnswerPairEvaluator.Case testCase = new BlindAnswerPairEvaluator.Case(
            "customer-001", "question", List.of("evidence"),
            new BlindAnswerPairEvaluator.Candidate("new", "new answer", 500),
            new BlindAnswerPairEvaluator.Candidate("old", "old answer", 300));

        BlindAnswerPairEvaluator.CaseResult result = evaluator.evaluate(testCase, blind -> {
            assertThat(blind.candidateA().label()).isEqualTo("A");
            assertThat(blind.candidateB().label()).isEqualTo("B");
            assertThat(List.of(blind.candidateA().answer(), blind.candidateB().answer()))
                .containsExactlyInAnyOrder("new answer", "old answer");
            Map<String, Double> high = Map.of("correctness", 1D, "completeness", .9D,
                "citationAccuracy", .8D, "directUsefulness", .9D);
            Map<String, Double> low = Map.of("correctness", .3D, "completeness", .4D,
                "citationAccuracy", .2D, "directUsefulness", .4D);
            return blind.candidateA().answer().equals("new answer")
                ? new BlindAnswerPairEvaluator.Judgement(high, low, "better grounded")
                : new BlindAnswerPairEvaluator.Judgement(low, high, "better grounded");
        });

        assertThat(result.winner()).isEqualTo("new");
        assertThat(result.candidateA().latencyMs()).isEqualTo(500);
        assertThat(result.candidateA().citationAccuracy()).isEqualTo(.8D);
    }
}
