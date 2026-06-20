package com.chatchat.knowledgebase.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceReasoningEngineTest {

    private final EvidenceReasoningEngine engine = new EvidenceReasoningEngine();

    @Test
    void buildsReasoningGraphFromEvidenceChunks() {
        DocumentSearchResult result = new DocumentSearchResult(
            EvidenceContextFormatter.CONTRACT_VERSION,
            "什么是数据资产管理",
            "GENERAL",
            3,
            List.of(
                chunk("chunk-1", 95.0D, "定义", "数据资产管理定义为对数据资源进行登记、治理和价值管理的过程。"),
                chunk("chunk-2", 88.0D, "项目背景", "该项目支持数据资产管理建设，并提供实时计算能力。"),
                chunk("chunk-3", 62.0D, "案例", "例如，系统会同步资产表并生成核对结果。")
            ),
            "",
            List.of()
        );

        EvidenceReasoningResult reasoning = engine.reason(result, new RetrievalEvidenceQuality(true, 0.9D, "usable"));

        assertThat(reasoning.contractVersion()).isEqualTo(EvidenceReasoningResult.CONTRACT_VERSION);
        assertThat(reasoning.graph().nodes()).hasSize(4);
        assertThat(reasoning.graph().edges())
            .extracting(EvidenceEdge::type)
            .contains(EvidenceEdgeType.DEFINES, EvidenceEdgeType.SUPPORTS, EvidenceEdgeType.EXEMPLIFIES);
        assertThat(reasoning.reasoningChain())
            .extracting(EvidenceReasoningStep::step)
            .contains("definition", "support", "example");
        assertThat(reasoning.conclusionMode()).isEqualTo("FULL");
        assertThat(reasoning.confidence()).isGreaterThan(0.7D);
        assertThat(reasoning.missingInfo()).isEmpty();
    }

    @Test
    void conflictEvidenceRequiresReviewAndCapsConfidence() {
        DocumentSearchResult result = new DocumentSearchResult(
            EvidenceContextFormatter.CONTRACT_VERSION,
            "资产口径是否一致",
            "GENERAL",
            2,
            List.of(
                chunk("chunk-1", 91.0D, "规则", "资产口径以会计资产为准。"),
                chunk("chunk-2", 84.0D, "冲突说明", "冲突：另一处说明资产口径以模型资产为准，二者不一致。")
            ),
            "",
            List.of()
        );

        EvidenceReasoningResult reasoning = engine.reason(result, new RetrievalEvidenceQuality(true, 0.88D, "usable"));

        assertThat(reasoning.conflictDetected()).isTrue();
        assertThat(reasoning.conclusionMode()).isEqualTo("REVIEW_REQUIRED");
        assertThat(reasoning.confidence()).isLessThanOrEqualTo(0.55D);
        assertThat(reasoning.reasoningChain())
            .extracting(EvidenceReasoningStep::step)
            .contains("conflict_review");
        assertThat(reasoning.missingInfo())
            .contains("Conflicting evidence requires review before a definitive answer");
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
