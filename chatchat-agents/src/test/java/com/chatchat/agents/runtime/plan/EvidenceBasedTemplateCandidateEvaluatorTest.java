package com.chatchat.agents.runtime.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceBasedTemplateCandidateEvaluatorTest {

    @Test
    void admitsCandidatesByEvidenceScoreWhenModelDoesNotProvideSelectedIds() {
        EvidenceBasedTemplateCandidateEvaluator evaluator =
            new EvidenceBasedTemplateCandidateEvaluator();
        Object output = Map.of(
            "returnedCount", 3,
            "templates", List.of(
                Map.of("templateId", "LOW_FIT", "decisionScore", 0.99),
                Map.of("templateId", "HIGH_FIT", "decisionScore", 0.40),
                Map.of("templateId", "MEDIUM_FIT", "decisionScore", 0.70)
            )
        );
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation = evaluator.evaluate(
            output,
            Map.of(
                "templateEvaluations", List.of(
                    Map.of(
                        "template_id", "LOW_FIT",
                        "total_score", 0.20,
                        "decision", "reject",
                        "reasons", List.of("wrong business output")
                    ),
                    Map.of(
                        "template_id", "HIGH_FIT",
                        "total_score", 0.95,
                        "decision", "accept",
                        "reasons", List.of("matches required margin balance evidence")
                    ),
                    Map.of(
                        "template_id", "MEDIUM_FIT",
                        "total_score", 0.75,
                        "decision", "accept",
                        "reasons", List.of("partially matches")
                    )
                )
            )
        );

        assertThat(evaluation.applied()).isTrue();
        assertThat(evaluation.candidateCount()).isEqualTo(3);
        assertThat(evaluation.selectedIds()).containsExactly("HIGH_FIT", "MEDIUM_FIT");
        Map<?, ?> projected = (Map<?, ?>) evaluation.output();
        assertThat(projected.get("returnedCount")).isEqualTo(2);
        assertThat(projected.get("runtimeTemplateSelection").toString())
            .contains("runtime_template_selection.v2", "mcpScoresAreWeakPriors=true",
                "runtime_evidence_model_review");
    }

    @Test
    void neverAdmitsAnIdThatWasNotReturnedByMcp() {
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedTemplateCandidateEvaluator().evaluate(
                Map.of("templates", List.of(Map.of("templateId", "AUTHORIZED"))),
                Map.of("selectedTemplateIds", List.of("INVENTED"))
            );

        assertThat(evaluation.applied()).isFalse();
        assertThat(evaluation.reason()).contains("not present in the authorized MCP candidate set");
    }
}
