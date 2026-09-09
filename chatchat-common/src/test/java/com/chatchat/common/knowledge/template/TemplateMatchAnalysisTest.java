package com.chatchat.common.knowledge.template;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
            "No authorized template satisfies the requested evidence.",
            "RUNTIME_EVIDENCE_MODEL_REVIEW");

        assertThat(analysis.selectedTemplateIds()).isEmpty();
        assertThat(analysis.toMap())
            .containsEntry("selectedTemplateIds", List.of())
            .containsEntry("decisionReason",
                "No authorized template satisfies the requested evidence.");
    }
}
