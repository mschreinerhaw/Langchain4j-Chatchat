package com.chatchat.common.knowledge.template;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateMatchAnalysisTest {

    @Test
    void representsAReviewedNoMatchWithoutForcingASelection() {
        TemplateMatchAnalysis analysis = new TemplateMatchAnalysis(
            TemplateMatchAnalysis.SCHEMA_VERSION,
            "查询客户交易偏好",
            Map.of(),
            List.of(),
            null,
            List.of(),
            List.of(),
            TemplateCoverageDecision.SCOPE_INSUFFICIENT,
            TemplateRetrievalOutcome.SCOPE_EXHAUSTED_NO_MATCH,
            List.of("authorized scope has no matching template"),
            Map.of("pageIndex", 0, "hasMore", false),
            "No authorized template satisfies the requested evidence.",
            "RUNTIME_EVIDENCE_MODEL_REVIEW");

        assertThat(analysis.selectedTemplateIds()).isEmpty();
        assertThat(analysis.toMap())
            .containsEntry("selectedTemplateIds", List.of())
            .containsEntry("coverageDecision", "SCOPE_INSUFFICIENT")
            .containsEntry("retrievalOutcome", "SCOPE_EXHAUSTED_NO_MATCH")
            .containsEntry("decisionReason",
                "No authorized template satisfies the requested evidence.");
    }

    @Test
    void rejectsSufficientCoverageWithoutASelectedTemplate() {
        assertThatThrownBy(() -> new TemplateMatchAnalysis(
            null, "question", Map.of(), List.of(), null, List.of(), List.of(),
            TemplateCoverageDecision.SUFFICIENT, TemplateRetrievalOutcome.SELECTED,
            List.of(), Map.of(), null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SUFFICIENT coverage");
    }
}
