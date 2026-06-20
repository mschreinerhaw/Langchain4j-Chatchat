package com.chatchat.knowledgebase.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceDecisionEngineTest {

    private final EvidenceDecisionEngine decisionEngine = new EvidenceDecisionEngine();
    private final EvidenceReasoningEngine reasoningEngine = new EvidenceReasoningEngine();

    @Test
    void titleOnlyHitRequiresExpansion() {
        DocumentSearchResult result = new DocumentSearchResult(
            EvidenceContextFormatter.CONTRACT_VERSION,
            "数据资产管理建设项目",
            "GENERAL",
            1,
            List.of(),
            "",
            List.of(),
            null,
            null,
            List.of(),
            DocumentSearchMatchType.TITLE_ONLY_HIT,
            DocumentRetrievalSemantics.titleOnly(),
            List.of(new DocumentSearchHit("doc-1", "数据资产管理建设项目", "report.docx", "word", 91.0D, List.of("立项报告"))),
            List.of(),
            null,
            DocumentExpansionPolicy.titleOnly(),
            EvidenceGovernancePolicy.needsExpansion(3, 6)
        );

        EvidenceDecisionResult decision = decisionEngine.decide(result, result.reasoning());

        assertThat(decision.action()).isEqualTo(EvidenceDecisionAction.EXPAND);
        assertThat(decision.requiresExpansion()).isTrue();
        assertThat(decision.canAnswer()).isFalse();
        assertThat(decision.trace().replayable()).isTrue();
        assertThat(decision.trace().selectedRuleId()).isEqualTo("title_only_requires_expansion");
        assertThat(decision.trace().steps())
            .extracting(EvidenceDecisionTraceStep::ruleId)
            .containsExactly("title_only_requires_expansion");
    }

    @Test
    void strongEvidenceCanAnswer() {
        DocumentSearchResult result = evidenceResult(
            chunk("chunk-1", 96.0D, "定义", "数据资产管理定义为对数据资源进行登记、治理和价值管理的过程。"),
            chunk("chunk-2", 90.0D, "支撑", "该项目支持数据资产管理建设，并提供实时计算能力。")
        );
        EvidenceReasoningResult reasoning = reasoningEngine.reason(result, new RetrievalEvidenceQuality(true, 0.92D, "usable"));

        EvidenceDecisionResult decision = decisionEngine.decide(result, reasoning);

        assertThat(decision.action()).isEqualTo(EvidenceDecisionAction.ANSWER);
        assertThat(decision.canAnswer()).isTrue();
        assertThat(decision.confidence()).isGreaterThanOrEqualTo(decision.policy().minAnswerConfidence());
        assertThat(decision.trace().selectedRuleId()).isEqualTo("answer_policy_satisfied");
        assertThat(decision.trace().steps())
            .extracting(EvidenceDecisionTraceStep::ruleId)
            .contains(
                "conflict_requires_review",
                "missing_a_grade_evidence",
                "answer_policy_satisfied"
            );
        assertThat(decision.trace().steps().get(decision.trace().steps().size() - 1).matched()).isTrue();
    }

    @Test
    void conflictingEvidenceRequiresReview() {
        DocumentSearchResult result = evidenceResult(
            chunk("chunk-1", 93.0D, "规则", "资产口径以会计资产为准。"),
            chunk("chunk-2", 86.0D, "冲突说明", "冲突：另一处说明资产口径以模型资产为准，二者不一致。")
        );
        EvidenceReasoningResult reasoning = reasoningEngine.reason(result, new RetrievalEvidenceQuality(true, 0.89D, "usable"));

        EvidenceDecisionResult decision = decisionEngine.decide(result, reasoning);

        assertThat(decision.action()).isEqualTo(EvidenceDecisionAction.REVIEW_REQUIRED);
        assertThat(decision.requiresReview()).isTrue();
        assertThat(decision.canAnswer()).isFalse();
        assertThat(decision.trace().selectedRuleId()).isEqualTo("conflict_requires_review");
        assertThat(decision.trace().steps())
            .anySatisfy(step -> {
                assertThat(step.ruleId()).isEqualTo("conflict_requires_review");
                assertThat(step.matched()).isTrue();
                assertThat(step.facts()).containsEntry("conflictDetected", true);
            });
    }

    private DocumentSearchResult evidenceResult(DocumentEvidenceChunk... chunks) {
        return new DocumentSearchResult(
            EvidenceContextFormatter.CONTRACT_VERSION,
            "数据资产管理是什么",
            "GENERAL",
            chunks.length,
            List.of(chunks),
            "",
            List.of()
        );
    }

    private DocumentEvidenceChunk chunk(String chunkId, Double score, String section, String content) {
        return new DocumentEvidenceChunk(
            null,
            chunkId,
            "doc-1",
            "数据资产管理报告.docx",
            section,
            Integer.parseInt(chunkId.substring(chunkId.lastIndexOf('-') + 1)),
            "paragraph",
            score,
            content,
            List.of(),
            new Citation("数据资产管理报告.docx", section),
            null,
            SearchPermissionContext.DEFAULT_TENANT,
            SearchPermissionContext.ANONYMOUS_USER,
            "public",
            List.of()
        );
    }
}
