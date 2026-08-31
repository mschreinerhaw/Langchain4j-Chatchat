package com.chatchat.common.runtime.summary.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfessionalDataAnalysisContractTest {

    @Test
    void definesSourceNeutralProfessionalReasoningAndPresentationGates() {
        ProfessionalDataAnalysisContract contract =
            ProfessionalDataAnalysisContract.enterpriseDefault();

        assertThat(contract.toMap().toString())
            .contains("professional_data_analysis.v1")
            .contains("AUDIT_COVERAGE_QUALITY_CONFLICTS_AND_GRAIN")
            .contains("AUTHORIZED_DERIVED_MEASURE")
            .contains("CONCLUSION_STRENGTH_NEVER_EXCEEDS_EVIDENCE_SCOPE")
            .contains("DERIVED_AND_PROXY_CLAIMS_REQUIRE_VALIDATED_PRODUCER_SEMANTIC_BASIS")
            .contains("ACCOUNT_FOR_IRRELEVANT_DATASETS_INTERNALLY_BUT_EXCLUDE_THEM_FROM_NARRATIVE")
            .contains("KEEP_FULL_DETAIL_OUT_OF_THE_NARRATIVE_UNLESS_REQUESTED")
            .doesNotContain("customer", "portfolio", "transaction");
    }
}
