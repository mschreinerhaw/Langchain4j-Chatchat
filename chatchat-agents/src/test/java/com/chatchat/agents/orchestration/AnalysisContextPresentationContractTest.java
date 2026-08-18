package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisContextPresentationContractTest {

    @Test
    void createsReadableViewOnlyFromSuppliedSemanticMetadata() {
        Map<String, Object> view = AnalysisContextPresentationContract.semanticView(
            "dataset-alpha",
            Map.of(
                "source", Map.of(
                    "displayName", "Capacity snapshot",
                    "description", "Measures the returned capacity state"),
                "schema", Map.of("fields", List.of(
                    Map.of("name", "RAW_A", "description", "Available capacity", "unit", "items"),
                    Map.of("technicalName", "RAW_B", "label", "Reserved capacity", "type", "decimal"))))) ;

        assertThat(view.toString())
            .contains("analysis_context_presentation.v1", "dataset-alpha")
            .contains("Capacity snapshot", "Measures the returned capacity state")
            .contains("RAW_A", "Available capacity", "items")
            .contains("RAW_B", "Reserved capacity", "decimal");
    }

    @Test
    void keepsUnknownFieldMeaningUnknown() {
        Map<String, Object> view = AnalysisContextPresentationContract.semanticView(
            "dataset-beta", Map.of("schema", Map.of("fields", Map.of("RAW_X", Map.of("type", "string")))));

        assertThat(view.toString()).contains("RAW_X", "type=string");
        assertThat(view.toString()).doesNotContain("displayName=RAW_X");
    }

    @Test
    void synthesisContractGatesConfiguredFindingsByRelevanceAndPresentationPolicy() {
        assertThat(AnalysisContextPresentationContract.synthesisInstruction())
            .contains("business meaning (technicalName)")
            .contains("Deterministic insight presentation")
            .contains("execution does not by itself make a finding relevant")
            .contains("WHEN_RELEVANT", "EXCEPTION_ONLY", "SUPPORTING")
            .contains("do not add domain knowledge");
    }
}
