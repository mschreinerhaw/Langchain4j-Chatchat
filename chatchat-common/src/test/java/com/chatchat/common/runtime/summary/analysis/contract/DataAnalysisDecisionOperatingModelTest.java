package com.chatchat.common.runtime.summary.analysis.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataAnalysisDecisionOperatingModelTest {

    @Test
    void definesSourceNeutralAnalysisDecisionRolesAndDriverBoundary() {
        assertThat(DataAnalysisDecisionOperatingModel.SCHEMA_VERSION)
            .isEqualTo("data_analysis_decision_operating_model.v1");
        assertThat(DataAnalysisDecisionOperatingModel.ParticipantRole.values())
            .containsExactly(
                DataAnalysisDecisionOperatingModel.ParticipantRole.WORKER,
                DataAnalysisDecisionOperatingModel.ParticipantRole.REDUCER,
                DataAnalysisDecisionOperatingModel.ParticipantRole.DRIVER,
                DataAnalysisDecisionOperatingModel.ParticipantRole.GOVERNANCE);
        assertThat(DataAnalysisDecisionOperatingModel.DriverInputMode.values())
            .contains(DataAnalysisDecisionOperatingModel.DriverInputMode
                .GOVERNED_WORKER_REDUCER_REPORTS_ONLY);
    }
}
