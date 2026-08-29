package com.chatchat.agents.runtime.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceBasedTemplateCandidateEvaluatorTest {

    @Test
    void rejectsToolDeclaredSelectionWhenContextAwareReviewerIsUnavailable() {
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedTemplateCandidateEvaluator().evaluate(
                Map.of(
                    "queryIr", Map.of("templates", Map.of(
                        "selectedIds", List.of("template-a", "template-b"))),
                    "templates", List.of(
                        Map.of("templateId", "template-a"),
                        Map.of("templateId", "template-b"),
                        Map.of("templateId", "template-c")
                    )
                ),
                Map.of("toolResultReviewUnavailable", true)
            );

        assertThat(evaluation.applied()).isFalse();
        assertThat(evaluation.selectedIds()).isEmpty();
        assertThat(evaluation.reason())
            .contains("original question", "cumulative analysis context");
    }

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
    void carriesOnlyAdmittedReviewedInvocationsIntoRuntimeSelection() {
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedTemplateCandidateEvaluator().evaluate(
                Map.of("templates", List.of(
                    Map.of("templateId", "asset-summary"),
                    Map.of("templateId", "trade-flow"),
                    Map.of("templateId", "unrelated")
                )),
                Map.of(
                    "selectedTemplateIds", List.of("asset-summary", "trade-flow"),
                    "nextActions", List.of(
                        Map.of(
                            "tool", "api_template_execute",
                            "intent", "load assets",
                            "input_changes", Map.of(
                                "templateId", "asset-summary",
                                "parameters", Map.of("khh", "070200046604"))),
                        Map.of(
                            "tool", "api_template_execute",
                            "intent", "load trades",
                            "input_changes", Map.of(
                                "templateId", "trade-flow",
                                "parameters", Map.of("khh", "070200046604"))),
                        Map.of(
                            "tool", "api_template_execute",
                            "input_changes", Map.of("templateId", "invented"))
                    )
                )
            );

        assertThat(evaluation.applied()).isTrue();
        assertThat(evaluation.output().toString())
            .contains("reviewedInvocations", "asset-summary", "trade-flow", "070200046604")
            .doesNotContain("templateId=invented");
    }

    @Test
    void recordsQuestionAndActualContextInCommonTemplateMatchAnalysis() {
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedTemplateCandidateEvaluator().evaluate(
                Map.of("templates", List.of(
                    Map.of("templateId", "ETF_SCALE"),
                    Map.of("templateId", "ETF_SHARE"),
                    Map.of("templateId", "ETF_MARGIN")
                )),
                Map.of(
                    "originalUserQuestion", "分析ETF规模与份额，观察市场资金流向",
                    "templateRequirementAnalysisContext", Map.of(
                        "completedEvidence", List.of(Map.of("dataset", "sse_etf_scale"))),
                    "selectedTemplateIds", List.of("ETF_SCALE", "ETF_SHARE"),
                    "rejectedTemplateIds", List.of("ETF_MARGIN"),
                    "businessAnalysisIntent", Map.of(
                        "business_goal", "观察ETF资金方向",
                        "analysis_subject", "ETF市场",
                        "analysis_focus", List.of("规模变化", "份额变化")),
                    "templateRelationships", List.of(Map.of(
                        "from_template_id", "ETF_SCALE",
                        "to_template_id", "ETF_SHARE",
                        "relation_type", "VALIDATES")),
                    "toolResultReviewReason", "问题要求规模与份额，不要求两融",
                    "templateEvaluations", List.of(
                        Map.of(
                            "template_id", "ETF_SCALE",
                            "business_group", "ETF",
                            "relevance", 0.98,
                            "evidence_fit", 0.95,
                            "parameter_readiness", 1.0,
                            "total_score", 0.97,
                            "decision", "accept",
                            "reasons", List.of("覆盖规模与份额"),
                            "matched_question_aspects", List.of("规模", "份额"),
                            "relationship_hints", List.of("按基金代码关联相邻交易日")
                        )
                    )
                )
            );

        assertThat(evaluation.selectedIds()).containsExactly("ETF_SCALE", "ETF_SHARE");
        assertThat(evaluation.templateMatchAnalysis())
            .containsEntry("schemaVersion", "template_match_analysis.v2")
            .containsEntry("objectType", "TEMPLATE_MATCH_ANALYSIS")
            .containsEntry("event", "BUSINESS_TEMPLATE_REQUIREMENT_MATCHING")
            .containsEntry("userQuestion", "分析ETF规模与份额，观察市场资金流向");
        assertThat(evaluation.templateMatchAnalysis().toString())
            .contains("sse_etf_scale", "businessGoal=观察ETF资金方向",
                "analysisRole=CONTEXT", "fromTemplateId=ETF_SCALE",
                "toTemplateId=ETF_SHARE", "matchedQuestionAspects=[规模, 份额]",
                "relationshipHints=[按基金代码关联相邻交易日]")
            .doesNotContain("ETF_MARGIN, ETF_SCALE");
        assertThat(evaluation.output().toString())
            .contains("templateMatchAnalysis", "analysisRole=IRRELEVANT");
        Map<?, ?> projected = (Map<?, ?>) evaluation.output();
        assertThat((List<?>) projected.get("templates")).extracting(String::valueOf)
            .noneMatch(value -> value.contains("ETF_MARGIN"));
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

    @Test
    void projectsEvidenceSelectionInsideOversizedOutputRoutingProjection() {
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedTemplateCandidateEvaluator().evaluate(
                Map.of(
                    "outputTruncated", true,
                    "routingProjection", Map.of("templates", List.of(
                        Map.of("templateId", "OTHER"),
                        Map.of("templateId", "sample_margin_trade_latest")
                    ))
                ),
                Map.of("selectedTemplateIds", List.of("sample_margin_trade_latest"))
            );

        assertThat(evaluation.applied()).isTrue();
        assertThat(evaluation.selectedIds()).containsExactly("sample_margin_trade_latest");
        Map<?, ?> output = (Map<?, ?>) evaluation.output();
        Map<?, ?> projection = (Map<?, ?>) output.get("routingProjection");
        assertThat(projection.get("templates").toString())
            .contains("sample_margin_trade_latest")
            .doesNotContain("OTHER");
    }

    @Test
    void projectsCandidatesInsideRequirementCoverageGroupsAndCanRejectWholeGroup() {
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedTemplateCandidateEvaluator().evaluate(
                Map.of("coverage", List.of(
                    Map.of("requirement", Map.of("id", "r1"), "templates", List.of(
                        Map.of("templateId", "R1_SELECTED"), Map.of("templateId", "R1_REJECTED"))),
                    Map.of("requirement", Map.of("id", "r2"), "templates", List.of(
                        Map.of("templateId", "R2_REJECTED")))
                )),
                Map.of(
                    "selectedTemplateIds", List.of("R1_SELECTED"),
                    "rejectedTemplateIds", List.of("R1_REJECTED", "R2_REJECTED")
                )
            );

        assertThat(evaluation.applied()).isTrue();
        assertThat(evaluation.candidateCount()).isEqualTo(3);
        assertThat(evaluation.selectedIds()).containsExactly("R1_SELECTED");
        assertThat(evaluation.output().toString())
            .contains("R1_SELECTED", "selectedCount=0")
            .doesNotContain("templateId=R1_REJECTED", "templateId=R2_REJECTED");
    }

    @Test
    void preservesUniqueCandidateWhenReviewContainsNoCandidateLevelDecision() {
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedTemplateCandidateEvaluator().evaluate(
                Map.of("templates", List.of(Map.of(
                    "templateId", "oracle-risk-session",
                    "executorTool", "sql_query_execute"
                ))),
                Map.of("reason", "downstream execution has not run yet")
            );

        assertThat(evaluation.applied()).isTrue();
        assertThat(evaluation.selectedIds()).containsExactly("oracle-risk-session");
        assertThat(evaluation.output().toString()).contains("selectionAuthority=runtime_unique_candidate");
    }

    @Test
    void honorsExplicitRejectionOfUniqueCandidate() {
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedTemplateCandidateEvaluator().evaluate(
                Map.of("templates", List.of(Map.of("templateId", "mysql-session"))),
                Map.of("rejectedTemplateIds", List.of("mysql-session"))
            );

        assertThat(evaluation.applied()).isTrue();
        assertThat(evaluation.selectedCount()).isZero();
    }

    @Test
    void requiresCandidateLevelDecisionWhenMultipleTemplatesRemain() {
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedTemplateCandidateEvaluator().evaluate(
                Map.of("templates", List.of(
                    Map.of("templateId", "oracle-session"),
                    Map.of("templateId", "mysql-session")
                )),
                Map.of("reason", "request is not complete")
            );

        assertThat(evaluation.applied()).isFalse();
        assertThat(evaluation.selectedCount()).isZero();
    }

    @Test
    void doesNotTreatPartialRejectionAsSelectionWhenSeveralCandidatesSurvive() {
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedTemplateCandidateEvaluator().evaluate(
                Map.of("templates", List.of(
                    Map.of("templateId", "oracle-session"),
                    Map.of("templateId", "oracle-lock"),
                    Map.of("templateId", "mysql-session")
                )),
                Map.of("rejectedTemplateIds", List.of("mysql-session"))
            );

        assertThat(evaluation.applied()).isFalse();
        assertThat(evaluation.selectedCount()).isZero();
    }

    @Test
    void admitsTheOnlyCandidateLeftByExplicitRejection() {
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedTemplateCandidateEvaluator().evaluate(
                Map.of("templates", List.of(
                    Map.of("templateId", "oracle-session"),
                    Map.of("templateId", "mysql-session")
                )),
                Map.of("rejectedTemplateIds", List.of("mysql-session"))
            );

        assertThat(evaluation.applied()).isTrue();
        assertThat(evaluation.selectedIds()).containsExactly("oracle-session");
    }

    @Test
    void doesNotAdmitUniqueCandidateWhenModelReviewerWasSkipped() {
        EvidenceBasedTemplateCandidateEvaluator.Evaluation evaluation =
            new EvidenceBasedTemplateCandidateEvaluator().evaluate(
                Map.of("templates", List.of(Map.of("templateId", "oracle-session"))),
                Map.of("toolResultReviewSkipped", true)
            );

        assertThat(evaluation.applied()).isFalse();
        assertThat(evaluation.selectedCount()).isZero();
    }
}
