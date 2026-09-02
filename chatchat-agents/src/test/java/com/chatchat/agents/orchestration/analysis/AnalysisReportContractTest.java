package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.orchestration.analysis.model.AnalysisReportContract;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisReportContractTest {

    @Test
    void driverReportRequiresAtLeastOneAdmittedSemanticUnit() {
        assertThat(AnalysisReportContract.driverReport("context only", 0, 0, 0)
            .mayEnterFinalPayload()).isFalse();
        assertThat(AnalysisReportContract.driverReport("governed finding", 1, 0, 0)
            .mayEnterFinalPayload()).isTrue();
        assertThat(AnalysisReportContract.driverReport(
            "可以并且必须基于现有数据进行分析。以下工具结果是本次分析的事实基础。",
            1, 1, 1).mayEnterFinalPayload()).isFalse();
    }

    @Test
    void governedFailureReportIsTheOnlyNonAnalysisPayloadThatCanBePublished() {
        AnalysisReportContract report = AnalysisReportContract.failureReport("analysis failed");

        assertThat(report.mayEnterFinalPayload()).isTrue();
        assertThat(report.reportType()).isEqualTo(AnalysisReportContract.ReportType.FAILURE_REPORT);
        assertThat(report.publishability())
            .isEqualTo(AnalysisReportContract.Publishability.PUBLISHABLE_FAILURE_REPORT);
    }
}
