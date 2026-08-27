package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.orchestration.answer.AnswerDecisionEngine;
import com.chatchat.agents.orchestration.answer.AnswerQualityEvaluator;

import com.chatchat.agents.runtime.answer.AgentAnswerReview;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnswerQualityEvaluatorTest {

    @Test
    void exposesEvidenceGroundedSynthesisAsAnAdditionalScoredCandidate() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("""
            {
              "preferredId":"quality_synthesis",
              "reason":"combined",
              "synthesizedAnswer":"Combined grounded answer",
              "candidates":[
                {"id":"candidate","accuracy":0.8,"grounding":0.9,"completeness":0.4,"citation":1.0,"usefulness":0.5},
                {"id":"stage","accuracy":0.6,"grounding":0.6,"completeness":0.9,"citation":1.0,"usefulness":0.8},
                {"id":"quality_synthesis","accuracy":0.95,"grounding":0.95,"completeness":0.95,"citation":1.0,"usefulness":0.95}
              ]
            }
            """);
        AnswerQualityEvaluator evaluator = new AnswerQualityEvaluator(new ObjectMapper());

        AnswerQualityEvaluator.QualityReport report = evaluator.evaluate(
            model,
            new AnswerQualityEvaluator.QualityRequest(
                "question",
                null,
                List.of("grounded observation"),
                "restore the missing comparison",
                List.of(
                    new AnswerQualityEvaluator.AnswerCandidate("candidate", "candidate", "Exact but incomplete"),
                    new AnswerQualityEvaluator.AnswerCandidate("stage", "summary_stage", "Complete but inconsistent")
                )
            )
        );

        assertThat(report.available()).isTrue();
        assertThat(report.candidateById(AnswerQualityEvaluator.QUALITY_SYNTHESIS))
            .extracting(AnswerQualityEvaluator.AnswerCandidate::answer)
            .isEqualTo("Combined grounded answer");
        assertThat(report.scores())
            .extracting(AnswerQualityEvaluator.CandidateScore::id)
            .contains(AnswerQualityEvaluator.QUALITY_SYNTHESIS);
    }

    @Test
    void lowGroundingSynthesisCannotReplaceTheExistingAnswer() {
        AnswerQualityEvaluator.AnswerCandidate current =
            new AnswerQualityEvaluator.AnswerCandidate("candidate", "candidate", "Grounded current answer");
        AnswerQualityEvaluator.AnswerCandidate synthesis =
            new AnswerQualityEvaluator.AnswerCandidate(
                AnswerQualityEvaluator.QUALITY_SYNTHESIS,
                AnswerQualityEvaluator.QUALITY_SYNTHESIS,
                "Unsupported polished answer"
            );
        AnswerQualityEvaluator.QualityReport report = new AnswerQualityEvaluator.QualityReport(
            true,
            AnswerQualityEvaluator.CONTRACT_VERSION,
            AnswerQualityEvaluator.QUALITY_SYNTHESIS,
            List.of(current, synthesis),
            List.of(
                score("candidate", 0.8, 0.8, 0.3),
                score(AnswerQualityEvaluator.QUALITY_SYNTHESIS, 0.74, 0.74, 1.0)
            ),
            "test",
            null
        );

        AnswerDecisionEngine.AnswerDecision decision = new AnswerDecisionEngine().decide(
            new AnswerDecisionEngine.AnswerDecisionRequest(
                current.answer(),
                null,
                AnswerDecisionEngine.EvidenceSignal.empty(),
                report,
                Map.of()
            )
        );

        assertThat(decision.finalAnswer()).isEqualTo(current.answer());
        assertThat(decision.metadata().get("answerDecisionTrace").toString())
            .contains("quality_synthesis_low_grounding", "quality_synthesis_low_accuracy");
    }

    @Test
    void evidenceReviewerRewriteIsQualityGatedWhenQualityEvidenceIsAvailable() {
        AnswerQualityEvaluator.AnswerCandidate current =
            new AnswerQualityEvaluator.AnswerCandidate("candidate", "candidate", "Grounded current answer");
        AnswerQualityEvaluator.AnswerCandidate reviewer =
            new AnswerQualityEvaluator.AnswerCandidate(
                AnswerQualityEvaluator.REVIEWER_SUGGESTION,
                AnswerQualityEvaluator.REVIEWER_SUGGESTION,
                "Unsupported reviewer rewrite"
            );
        AnswerQualityEvaluator.QualityReport report = new AnswerQualityEvaluator.QualityReport(
            true,
            AnswerQualityEvaluator.CONTRACT_VERSION,
            "candidate",
            List.of(current, reviewer),
            List.of(
                score("candidate", 0.9, 0.9, 0.8),
                score(AnswerQualityEvaluator.REVIEWER_SUGGESTION, 0.2, 0.2, 0.95)
            ),
            "reviewer rewrite has insufficient grounding",
            null
        );

        AnswerDecisionEngine.AnswerDecision decision = new AnswerDecisionEngine().decide(
            new AnswerDecisionEngine.AnswerDecisionRequest(
                current.answer(),
                new com.chatchat.agents.runtime.answer.AgentAnswerReview(
                    com.chatchat.agents.runtime.answer.AgentAnswerReview.REVISED,
                    reviewer.answer(),
                    "reviewer proposed a rewrite"
                ),
                AnswerDecisionEngine.EvidenceSignal.empty(),
                report,
                Map.of("modelEvidenceReviewRewriteAllowed", true)
            )
        );

        assertThat(decision.finalAnswer()).isEqualTo(current.answer());
        assertThat(decision.metadata())
            .containsEntry("answerReviewRewriteApplied", false)
            .containsEntry("answerReviewRewriteSkippedReason",
                "quality_aggregation_selected_original_candidate");
    }

    @Test
    void persistedCandidateAuditContainsOnlyBoundedPreview() {
        String longAnswer = "x".repeat(5000);
        AnswerQualityEvaluator.QualityReport report =
            AnswerQualityEvaluator.QualityReport.unavailable(
                "test",
                List.of(new AnswerQualityEvaluator.AnswerCandidate(
                    "stage", AnswerQualityEvaluator.SUMMARY_STAGE, longAnswer
                ))
            );

        assertThat(String.valueOf(report.candidateMaps().get(0).get("answerPreview")))
            .hasSize(1000);
    }

    private AnswerQualityEvaluator.CandidateScore score(String id,
                                                        double accuracy,
                                                        double grounding,
                                                        double completeness) {
        return new AnswerQualityEvaluator.CandidateScore(
            id,
            0.0,
            accuracy,
            grounding,
            completeness,
            1.0,
            completeness,
            false,
            false,
            false,
            false,
            false,
            List.of()
        );
    }
}
