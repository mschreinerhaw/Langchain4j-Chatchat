package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.orchestration.analysis.model.AnalysisExecutionOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisExecutionOutcomeTest {

    @Test
    void reanalysisOutcomeKeepsDatasetAndProhibitsDuplicateAcquisition() {
        AnalysisExecutionOutcome outcome = new AnalysisExecutionOutcome(
            AnalysisExecutionOutcome.SCHEMA_VERSION,
            AnalysisExecutionOutcome.ExecutionStatus.NEEDS_REANALYSIS,
            AnalysisExecutionOutcome.FailureCategory.ANALYSIS_FAILURE,
            AnalysisExecutionOutcome.PhaseStatus.COMPLETED,
            AnalysisExecutionOutcome.PhaseStatus.REJECTED,
            AnalysisExecutionOutcome.PhaseStatus.BLOCKED,
            AnalysisExecutionOutcome.PhaseStatus.BLOCKED,
            AnalysisExecutionOutcome.PhaseStatus.NOT_STARTED,
            List.of(),
            new AnalysisExecutionOutcome.RetryDirective(
                "RETRY_ANALYSIS", "WORKER_ANALYSIS", true, false, 1),
            AnalysisExecutionOutcome.Publishability.GOVERNED_FAILURE_REPORT_ONLY,
            "NO_ADMITTED_WORKER_REPORT");

        assertThat(outcome.toMap())
            .containsEntry("status", "NEEDS_REANALYSIS")
            .containsEntry("failureCategory", "ANALYSIS_FAILURE");
        assertThat((Map<String, Object>) outcome.toMap().get("retryDirective"))
            .containsEntry("reuseExistingDataset", true)
            .containsEntry("dataAcquisitionAllowed", false);
        assertThat(outcome.failureReport())
            .contains("数据分析暂时不可用", "不作为报告发布闸门", "禁止重新查询相同数据")
            .doesNotContain("未通过发布准入", "发布治理", "查询结果明细");
    }

}
