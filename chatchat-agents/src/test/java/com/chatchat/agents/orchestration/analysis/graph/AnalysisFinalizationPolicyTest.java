package com.chatchat.agents.orchestration.analysis.graph;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisFinalizationPolicyTest {
    @Test void admittedStructuredReportIsNotSentToAnotherModel() {
        var metadata = new LinkedHashMap<String,Object>(Map.of(
            "analysisGraphStatus", "COMPLETED_WITH_LIMITATIONS", "finalClaimSelectionAccepted", true,
            "interpretationPlanFinalResultProduced", true,
            "analyticalReport", Map.of("schemaVersion", "analytical_report.v1", "blocks", List.of(Map.of("id", "F1")))));
        assertThat(AnalysisFinalizationPolicy.directPublicationReason(metadata)).isEqualTo("GRAPH_REPORT_ALREADY_GOVERNED");
        metadata.put("analysisGraphStatus", "FAILED");
        assertThat(AnalysisFinalizationPolicy.directPublicationReason(metadata)).isEmpty();
        metadata.put("analysisGraphStatus", "COMPLETED");
        metadata.put("finalClaimSelectionAccepted", false);
        assertThat(AnalysisFinalizationPolicy.directPublicationReason(metadata)).isEmpty();
        assertThat(AnalysisFinalizationPolicy.directPublicationReason(Map.of())).isEmpty();
    }
    @Test void blockedPreflightNeverUsesLegacyReviewer() {
        assertThat(AnalysisFinalizationPolicy.directPublicationReason(Map.of("analysisFinalAdmissionBlocked", true)))
            .isEqualTo("ANALYSIS_PREFLIGHT_TERMINAL");
    }
}
