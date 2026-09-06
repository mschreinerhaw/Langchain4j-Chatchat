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
        Map<String, Object> coherence = (Map<String, Object>) contract.get("modelReportQualityPolicy");
        @SuppressWarnings("unchecked")
        Map<String, Object> adaptive = (Map<String, Object>) contract.get("adaptivePromptPolicy");

        assertThat(authority)
            .containsEntry("boundary", "MODEL_DECIDES_HOW_TO_ANALYZE_RUNTIME_DECIDES_WHAT_IS_LEGAL_TO_EXECUTE")
            .containsEntry("analysisOwner", "MODEL_SELECTS_QUESTION_RELEVANT_ANALYSIS")
            .containsEntry("runtimeRole", "VALIDATE_PROTOCOL_PERMISSION_PARAMETERS_RESOURCE_BUDGET_READ_ONLY_POLICY_AND_EVIDENCE_LINEAGE_THEN_EXECUTE");
        assertThat(String.valueOf(authority.get("noImplicitFormulaRule")))
            .contains("must never infer SUM, AVG, ratio");
        assertThat(coherence)
            .containsKeys("canonicalMetricRule", "logicalStrengthRule", "crossSectionRule", "readerRule", "selfReview");
        assertThat(adaptive)
            .containsEntry("invocationScope", "ONCE_PER_QUESTION_NOT_PER_DATASET_OR_CHUNK")
            .containsEntry("inputBoundary", "QUESTION_ROLE_OBJECTIVE_AND_SEMANTIC_METADATA_NO_RAW_RECORDS")
            .containsEntry("fixedPromptPolicy", "ONLY_EXPLICIT_PRODUCER_GOVERNED_CONTRACTS");
    }
}
