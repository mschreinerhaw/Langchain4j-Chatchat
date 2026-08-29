package com.chatchat.agents.orchestration.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisRecordScopeProfilerTest {

    @Test
    void reportsOnlyStructuralConstantsWithoutAssigningBusinessMeaning() {
        Map<String, Object> profile = new AnalysisRecordScopeProfiler().profile(List.of(
            Map.of("fundCode", "513330", "scaleChange", 80000,
                "totalScaleChange", 391519.6, "comparableFundCount", 500),
            Map.of("fundCode", "515880", "scaleChange", 79600,
                "totalScaleChange", 391519.6, "comparableFundCount", 500)));

        assertThat(profile).containsEntry("schemaVersion", "analysis_record_scope_profile.v1")
            .containsEntry("returnedRecordCount", 2);
        assertThat(profile.get("constantAcrossReturnedRows").toString())
            .contains("totalScaleChange", "comparableFundCount")
            .doesNotContain("fundCode", "scaleChange");
        assertThat(profile).containsEntry(
            "profileRole", "STRUCTURAL_STATISTICS_ONLY_NO_SEMANTIC_INFERENCE");
    }
}
