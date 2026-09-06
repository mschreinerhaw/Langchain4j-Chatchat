package com.chatchat.common.runtime.summary.analysis.contract;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisMethodologyContractTest {

    @Test
    void assignsAnalysisToModelAndExecutionAuditToRuntime() {
        Map<String, Object> contract = AnalysisMethodologyContract.enterpriseDefault().toMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> authority = (Map<String, Object>) contract.get("analysisAuthorityPolicy");
        @SuppressWarnings("unchecked")
        Map<String, Object> coherence = (Map<String, Object>) contract.get("narrativeCoherencePolicy");

        assertThat(authority)
            .containsEntry("analysisOwner", "MODEL_SELECTS_QUESTION_RELEVANT_ANALYSIS")
            .containsEntry("runtimeRole", "EXECUTE_DECLARED_CALCULATION_VALIDATE_LINEAGE_AND_AUDIT_RESULT");
        assertThat(String.valueOf(authority.get("noImplicitFormulaRule")))
            .contains("must never infer SUM, AVG, ratio");
        assertThat(coherence)
            .containsKeys("canonicalMetricRule", "logicalStrengthRule", "crossSectionRule", "readerRule", "selfReview");
    }
}
